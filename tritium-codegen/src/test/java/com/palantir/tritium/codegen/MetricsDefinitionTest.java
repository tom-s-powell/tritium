/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.tritium.codegen;

import static org.assertj.core.api.Java6Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import java.io.File;
import java.io.IOException;
import org.junit.Test;

public class MetricsDefinitionTest {

    private final ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory()).registerModule(new Jdk8Module());

    @Test
    public void deserializes_from_yaml() throws IOException {
        MetricsDefinition deserialized = objectMapper.readValue(
                new File("src/test/resources/input.yml"),
                MetricsDefinition.class);

        MetricsDefinition expected = ImmutableMetricsDefinition.builder()
                .addGauges(
                        "something.graph",
                        "something.count",
                        "something.foomonitor.initialize.time",
                        "something.bazgroups.running.count")
                .addMeters("something.foo", "something.bar")
                .build();

        assertThat(deserialized).isEqualTo(expected);
    }
}
