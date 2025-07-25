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

package org.apache.servicecomb.config.kie.client;

import com.google.common.eventbus.EventBus;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import org.apache.http.HttpStatus;

import org.apache.servicecomb.config.common.exception.OperationException;
import org.apache.servicecomb.config.kie.client.model.ConfigConstants;
import org.apache.servicecomb.config.kie.client.model.ConfigurationsRequest;
import org.apache.servicecomb.config.kie.client.model.ConfigurationsResponse;
import org.apache.servicecomb.config.kie.client.model.KVDoc;
import org.apache.servicecomb.config.kie.client.model.KVResponse;
import org.apache.servicecomb.config.kie.client.model.KieAddressManager;
import org.apache.servicecomb.config.kie.client.model.KieConfiguration;
import org.apache.servicecomb.config.kie.client.model.ValueType;
import org.apache.servicecomb.http.client.common.HttpRequest;
import org.apache.servicecomb.http.client.common.HttpResponse;
import org.apache.servicecomb.http.client.common.HttpTransport;
import org.apache.servicecomb.http.client.common.HttpUtils;
import org.apache.servicecomb.http.client.utils.ServiceCombServiceAvailableUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.CollectionUtils;

public class KieClient implements KieConfigOperation {

  private static final Logger LOGGER = LoggerFactory.getLogger(KieClient.class);

  private static final String ADDRESS_CHECK_PATH = "/v1/health";

  protected HttpTransport httpTransport;

  protected String revision = "0";

  private final KieAddressManager addressManager;

  private final KieConfiguration kieConfiguration;

  public static final String DEFAULT_KIE_API_VERSION = "v1";

  private final Map<String, List<String>> dimensionConfigNames = new HashMap<>();

  public KieClient(KieAddressManager addressManager, HttpTransport httpTransport, KieConfiguration kieConfiguration) {
    this.httpTransport = httpTransport;
    this.addressManager = addressManager;
    this.kieConfiguration = kieConfiguration;
  }

  public void setEventBus(EventBus eventBus) {
    addressManager.setEventBus(eventBus);
  }

  @Override
  public ConfigurationsResponse queryConfigurations(ConfigurationsRequest request, String address) {
    String url = buildUrl(request, address);
    try {
      if (kieConfiguration.isEnableLongPolling()) {
        url += "&wait=" + kieConfiguration.getPollingWaitInSeconds() + "s";
      }

      HttpRequest httpRequest = new HttpRequest(url, null, null, HttpRequest.GET);
      HttpResponse httpResponse = httpTransport.doRequest(httpRequest);
      ConfigurationsResponse configurationsResponse = new ConfigurationsResponse();
      if (httpResponse.getStatusCode() == HttpStatus.SC_OK) {
        revision = httpResponse.getHeader("X-Kie-Revision");
        KVResponse allConfigList = HttpUtils.deserialize(httpResponse.getContent(), KVResponse.class);
        logConfigurationNames(request.getLabelsQuery(), allConfigList.getData());
        Map<String, Object> configurations = getConfigByLabel(allConfigList);
        configurationsResponse.setConfigurations(configurations);
        configurationsResponse.setChanged(true);
        configurationsResponse.setRevision(revision);
        addressManager.recordSuccessState(address);
        return configurationsResponse;
      }
      if (httpResponse.getStatusCode() == HttpStatus.SC_BAD_REQUEST) {
        throw new OperationException("Bad request for query configurations.");
      }
      if (httpResponse.getStatusCode() == HttpStatus.SC_NOT_MODIFIED) {
        configurationsResponse.setChanged(false);
        addressManager.recordSuccessState(address);
        return configurationsResponse;
      }
      if (httpResponse.getStatusCode() == HttpStatus.SC_TOO_MANY_REQUESTS) {
        LOGGER.warn("rate limited, keep the local dimension [{}] configs unchanged.", request.getLabelsQuery());
        configurationsResponse.setChanged(false);
        addressManager.recordSuccessState(address);
        return configurationsResponse;
      }
      addressManager.recordFailState(address);
      throw new OperationException(
          "read response failed. status:" + httpResponse.getStatusCode() + "; message:" +
              httpResponse.getMessage() + "; content:" + httpResponse.getContent());
    } catch (Exception e) {
      addressManager.recordFailState(address);
      LOGGER.error("query configuration from {} failed, message={}", url, e.getMessage());
      throw new OperationException("read response failed. ", e);
    }
  }

  /**
   * Only the name of the new configuration item is printed.
   * No log is printed when the configuration content is updated.
   *
   * @param dimension dimension
   * @param data configs-data
   */
  private void logConfigurationNames(String dimension, List<KVDoc> data) {
    if (CollectionUtils.isEmpty(data)) {
      return;
    }
    List<String> configNames = dimensionConfigNames.get(dimension);
    if (configNames == null) {
      configNames = new ArrayList<>();
    }
    StringBuilder names = new StringBuilder();
    for (KVDoc doc : data) {
      if (configNames.contains(doc.getKey())) {
        continue;
      }
      names.append(doc.getKey()).append(",");
      configNames.add(doc.getKey());
    }
    if (names.isEmpty()) {
      return;
    }
    dimensionConfigNames.put(dimension, configNames);
    String fileNames = names.substring(0, names.length() - 1);
    LOGGER.info("pulling dimension [{}] configurations success, get config names: [{}].",
        dimension, fileNames);
  }

  @Override
  public void checkAddressAvailable(String address) {
    ServiceCombServiceAvailableUtils.checkAddressAvailable(addressManager, address, httpTransport, ADDRESS_CHECK_PATH);
  }

  private String buildUrl(ConfigurationsRequest request, String currentAddress) {
    StringBuilder sb = new StringBuilder();
    sb.append(currentAddress);
    sb.append("/");
    sb.append(DEFAULT_KIE_API_VERSION);
    sb.append("/");
    sb.append(kieConfiguration.getProject());
    sb.append("/kie/kv?");
    sb.append(request.getLabelsQuery());
    sb.append("&revision=");
    sb.append(request.getRevision());
    if (request.isWithExact()) {
      sb.append("&match=exact");
    }
    return sb.toString();
  }

  private Map<String, Object> getConfigByLabel(KVResponse resp) {
    Map<String, Object> resultMap = new HashMap<>();
    resp.getData().stream()
        .sorted(Comparator.comparing(KVDoc::getUpdateTime, Comparator.nullsFirst(Comparator.naturalOrder())))
        .filter(doc -> doc.getStatus() == null || ConfigConstants.STATUS_ENABLED.equalsIgnoreCase(doc.getStatus()))
        .map(this::processValueType)
        .collect(Collectors.toList())
        .forEach(resultMap::putAll);
    return resultMap;
  }

  private Map<String, Object> processValueType(KVDoc kvDoc) {
    ValueType valueType;
    try {
      valueType = ValueType.valueOf(kvDoc.getValueType());
    } catch (IllegalArgumentException e) {
      throw new OperationException("value type not support [" + kvDoc.getValue() + "]");
    }
    Properties properties = new Properties();
    Map<String, Object> kvMap = new HashMap<>();
    try {
      switch (valueType) {
        case yml:
        case yaml:
          YamlPropertiesFactoryBean yamlFactory = new YamlPropertiesFactoryBean();
          yamlFactory.setResources(new ByteArrayResource(kvDoc.getValue().getBytes(StandardCharsets.UTF_8)));
          return toMap(yamlFactory.getObject());
        case properties:
          properties.load(new StringReader(kvDoc.getValue()));
          return toMap(properties);
        case text:
        case string:
        default:
          kvMap.put(kvDoc.getKey(), kvDoc.getValue());
          return kvMap;
      }
    } catch (Exception e) {
      LOGGER.error("read config failed", e);
    }
    return Collections.emptyMap();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> toMap(Properties properties) {
    if (properties == null) {
      return Collections.emptyMap();
    }
    Map<String, Object> result = new HashMap<>();
    Enumeration<String> keys = (Enumeration<String>) properties.propertyNames();
    while (keys.hasMoreElements()) {
      String key = keys.nextElement();
      Object value = properties.getProperty(key);
      result.put(key, value);
    }
    return result;
  }
}
