/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dubbo.metrics.metadata.collector.stat;

import org.apache.dubbo.common.utils.ConcurrentHashMapUtils;
import org.apache.dubbo.metrics.collector.MetricsCollector;
import org.apache.dubbo.metrics.metadata.type.ApplicationType;
import org.apache.dubbo.metrics.metadata.type.ServiceType;
import org.apache.dubbo.metrics.model.ApplicationMetric;
import org.apache.dubbo.metrics.model.MetricsCategory;
import org.apache.dubbo.metrics.model.MetricsKey;
import org.apache.dubbo.metrics.model.MetricsKeyWrapper;
import org.apache.dubbo.metrics.model.ServiceKeyMetric;
import org.apache.dubbo.metrics.model.container.AtomicLongContainer;
import org.apache.dubbo.metrics.model.container.LongAccumulatorContainer;
import org.apache.dubbo.metrics.model.container.LongContainer;
import org.apache.dubbo.metrics.model.sample.GaugeMetricSample;
import org.apache.dubbo.metrics.report.MetricsExport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.apache.dubbo.metrics.metadata.MetadataConstants.OP_TYPE_PUSH;
import static org.apache.dubbo.metrics.metadata.MetadataConstants.OP_TYPE_STORE_PROVIDER_INTERFACE;
import static org.apache.dubbo.metrics.metadata.MetadataConstants.OP_TYPE_SUBSCRIBE;

/**
 * As a data aggregator, use internal data containers calculates and classifies
 * the registry data collected by {@link MetricsCollector MetricsCollector}, and
 * provides an {@link MetricsExport MetricsExport} interface for exporting standard output formats.
 */
public class MetadataStatComposite implements MetricsExport {


    public Map<ApplicationType, Map<String, AtomicLong>> applicationNumStats = new ConcurrentHashMap<>();
    public Map<ServiceType, Map<ServiceKeyMetric, AtomicLong>> serviceNumStats = new ConcurrentHashMap<>();
    public List<LongContainer<? extends Number>> appRtStats = new ArrayList<>();

    public List<LongContainer<? extends Number>> serviceRtStats = new ArrayList<>();


    public MetadataStatComposite() {
        for (ApplicationType applicationType : ApplicationType.values()) {
            applicationNumStats.put(applicationType, new ConcurrentHashMap<>());
        }
        for (ServiceType serviceType : ServiceType.values()) {
            serviceNumStats.put(serviceType, new ConcurrentHashMap<>());
        }

        appRtStats.addAll(initStats(OP_TYPE_PUSH, appRtStats));
        appRtStats.addAll(initStats(OP_TYPE_SUBSCRIBE, appRtStats));

        serviceRtStats.addAll(initStats(OP_TYPE_STORE_PROVIDER_INTERFACE, serviceRtStats));
    }

    private List<LongContainer<? extends Number>> initStats(String registryOpType, List<LongContainer<? extends Number>> rtStats) {

        List<LongContainer<? extends Number>> singleRtStats = new ArrayList<>();
        singleRtStats.add(new AtomicLongContainer(new MetricsKeyWrapper(registryOpType, MetricsKey.METRIC_RT_LAST)));
        singleRtStats.add(new LongAccumulatorContainer(new MetricsKeyWrapper(registryOpType, MetricsKey.METRIC_RT_MIN), new LongAccumulator(Long::min, Long.MAX_VALUE)));
        singleRtStats.add(new LongAccumulatorContainer(new MetricsKeyWrapper(registryOpType, MetricsKey.METRIC_RT_MAX), new LongAccumulator(Long::max, Long.MIN_VALUE)));
        singleRtStats.add(new AtomicLongContainer(new MetricsKeyWrapper(registryOpType, MetricsKey.METRIC_RT_SUM), (responseTime, longAccumulator) -> longAccumulator.addAndGet(responseTime)));
        // AvgContainer is a special counter that stores the number of times but outputs function of sum/times
        AtomicLongContainer avgContainer = new AtomicLongContainer(new MetricsKeyWrapper(registryOpType, MetricsKey.METRIC_RT_AVG), (k, v) -> v.incrementAndGet());
        avgContainer.setValueSupplier(applicationName -> {
            LongContainer<? extends Number> totalContainer = rtStats.stream().filter(longContainer -> longContainer.isKeyWrapper(MetricsKey.METRIC_RT_SUM, registryOpType)).findFirst().get();
            AtomicLong totalRtTimes = avgContainer.get(applicationName);
            AtomicLong totalRtSum = (AtomicLong) totalContainer.get(applicationName);
            return totalRtSum.get() / totalRtTimes.get();
        });
        singleRtStats.add(avgContainer);
        return singleRtStats;
    }

    public void increment(ApplicationType type, String applicationName) {
        incrementSize(type, applicationName, 1);
    }

    public void incrementServiceKey(ServiceType type, String applicationName, String serviceKey, int size) {
        if (!serviceNumStats.containsKey(type)) {
            return;
        }
        serviceNumStats.get(type).computeIfAbsent(new ServiceKeyMetric(applicationName, serviceKey), k -> new AtomicLong(0L)).getAndAdd(size);
    }

    public void incrementSize(ApplicationType type, String applicationName, int size) {
        if (!applicationNumStats.containsKey(type)) {
            return;
        }
        applicationNumStats.get(type).computeIfAbsent(applicationName, k -> new AtomicLong(0L)).getAndAdd(size);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public void calcApplicationRt(String applicationName, String registryOpType, Long responseTime) {
        for (LongContainer container : appRtStats.stream().filter(longContainer -> longContainer.specifyType(registryOpType)).collect(Collectors.toList())) {
            Number current = (Number) ConcurrentHashMapUtils.computeIfAbsent(container, applicationName, container.getInitFunc());
            container.getConsumerFunc().accept(responseTime, current);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public void calcServiceKeyRt(String applicationName, String serviceKey, String registryOpType, Long responseTime) {
        for (LongContainer container : serviceRtStats.stream().filter(longContainer -> longContainer.specifyType(registryOpType)).collect(Collectors.toList())) {
            Number current = (Number) ConcurrentHashMapUtils.computeIfAbsent(container, applicationName + "_" + serviceKey, container.getInitFunc());
            container.getConsumerFunc().accept(responseTime, current);
        }
    }

    @SuppressWarnings({"rawtypes"})
    private void doExportRt(List<GaugeMetricSample> list, List<LongContainer<? extends Number>> rtStats, Function<String, Map<String, String>> tagNameFunc) {
        for (LongContainer<? extends Number> rtContainer : rtStats) {
            MetricsKeyWrapper metricsKeyWrapper = rtContainer.getMetricsKeyWrapper();
            for (Map.Entry<String, ? extends Number> entry : rtContainer.entrySet()) {
                list.add(new GaugeMetricSample<>(metricsKeyWrapper.targetKey(), metricsKeyWrapper.targetDesc(), tagNameFunc.apply(entry.getKey()), MetricsCategory.RT, entry.getKey().intern(), value -> rtContainer.getValueSupplier().apply(value.intern())));
            }
        }
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List<GaugeMetricSample> exportNumMetrics() {
        List<GaugeMetricSample> list = new ArrayList<>();
        for (ApplicationType type : applicationNumStats.keySet()) {
            Map<String, AtomicLong> stringAtomicLongMap = applicationNumStats.get(type);
            for (String applicationName : stringAtomicLongMap.keySet()) {
                list.add(convertToSample(applicationName, type, MetricsCategory.METADATA, stringAtomicLongMap.get(applicationName)));
            }
        }
        return list;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List<GaugeMetricSample> exportRtMetrics() {
        List<GaugeMetricSample> list = new ArrayList<>();
        doExportRt(list, appRtStats, ApplicationMetric::getTagsByName);
        doExportRt(list, serviceRtStats, ApplicationMetric::getServiceTags);
        return list;
    }

    @SuppressWarnings({"rawtypes"})
    public List<GaugeMetricSample> exportServiceNumMetrics() {
        List<GaugeMetricSample> list = new ArrayList<>();
        for (ServiceType type : serviceNumStats.keySet()) {
            Map<ServiceKeyMetric, AtomicLong> stringAtomicLongMap = serviceNumStats.get(type);
            for (ServiceKeyMetric serviceKeyMetric : stringAtomicLongMap.keySet()) {
                list.add(new GaugeMetricSample<>(type.getMetricsKey(), serviceKeyMetric.getTags(), MetricsCategory.METADATA, stringAtomicLongMap, value -> value.get(serviceKeyMetric).get()));
            }
        }
        return list;
    }

    @SuppressWarnings("rawtypes")
    public GaugeMetricSample convertToSample(String applicationName, ApplicationType type, MetricsCategory category, AtomicLong targetNumber) {
        return new GaugeMetricSample<>(type.getMetricsKey(), ApplicationMetric.getTagsByName(applicationName), category, targetNumber, AtomicLong::get);
    }

}
