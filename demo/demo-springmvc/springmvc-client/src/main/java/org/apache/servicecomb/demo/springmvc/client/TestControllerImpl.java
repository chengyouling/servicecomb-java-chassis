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
package org.apache.servicecomb.demo.springmvc.client;

import org.apache.servicecomb.demo.CategorizedTestCase;
import org.apache.servicecomb.demo.TestMgr;
import org.apache.servicecomb.demo.controller.Person;
import org.apache.servicecomb.demo.controller.PersonAlias;
import org.apache.servicecomb.foundation.common.LegacyPropertyFactory;
import org.apache.servicecomb.provider.springmvc.reference.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestOperations;


@Component
public class TestControllerImpl implements CategorizedTestCase {
  RestOperations restTemplate = RestTemplateBuilder.create();

  private static final String SERVER = "servicecomb://springmvc";

  public void testRestTransport() throws Exception {
    testQueryParamSpecial();
    testResponseModel();
  }

  private void testResponseModel() {
    Person person = restTemplate.getForObject(SERVER + "/springmvc/controller/testResponseModel", Person.class);
    TestMgr.check("jack", person.getName());

    PersonAlias personAlias = restTemplate.getForObject(SERVER + "/springmvc/controller/testResponseModel",
        PersonAlias.class);
    TestMgr.check("jack", personAlias.getName());
  }

  private void testQueryParamSpecial() {
    // vert.x and servlet container have different query parameter implementations
    if (LegacyPropertyFactory.getBooleanProperty("servicecomb.test.vert.transport", true)) {

      TestMgr.check(restTemplate.getForObject(
          SERVER + "/springmvc/controller/sayHello1?name=you;me", String.class), "Hello you,v");
    } else {
      TestMgr.check(restTemplate.getForObject(
          SERVER + "/springmvc/controller/sayHello1?name=you;me", String.class), "Hello you;me,v");
    }
    TestMgr.check(restTemplate.getForObject(
        SERVER + "/springmvc/controller/sayHello1?name={1}",
        String.class, "you;me"), "Hello you;me,v");
  }
}
