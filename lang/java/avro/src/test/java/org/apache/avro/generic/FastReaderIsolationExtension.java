/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.avro.generic;

import org.apache.avro.reflect.ReflectData;
import org.apache.avro.specific.SpecificData;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Restores the process-wide fast-reader flag on the default data models after
 * each test, and fails if a test mutated it.
 * <p>
 * The avro module runs tests twice (default {@code org.apache.avro.fastread}
 * and {@code false}). Mutating {@link GenericData#get()},
 * {@link SpecificData#get()}, or {@link ReflectData#get()} defeats that
 * configuration for later tests in the same JVM (AVRO-4326). Tests that need a
 * specific setting should use a dedicated data-model instance.
 */
public class FastReaderIsolationExtension implements AfterEachCallback {

  @Override
  public void afterEach(ExtensionContext context) {
    boolean expected = expectedFastReader();
    StringBuilder mutated = new StringBuilder();
    for (NamedModel model : defaultModels()) {
      if (model.data.isFastReaderEnabledFlag() != expected) {
        if (mutated.length() > 0) {
          mutated.append(", ");
        }
        mutated.append(model.name);
        model.data.setFastReaderEnabled(expected);
      }
    }
    if (mutated.length() > 0) {
      throw new AssertionError("Test mutated the global fast-reader flag on " + mutated
          + ". Use a dedicated GenericData/SpecificData/ReflectData instance "
          + "rather than changing the default models (AVRO-4326).");
    }
  }

  static boolean expectedFastReader() {
    return "true".equalsIgnoreCase(System.getProperty(GenericData.FAST_READER_PROP, "true"));
  }

  static NamedModel[] defaultModels() {
    return new NamedModel[] { new NamedModel("GenericData.get()", GenericData.get()),
        new NamedModel("SpecificData.get()", SpecificData.get()),
        new NamedModel("ReflectData.get()", ReflectData.get()),
        new NamedModel("ReflectData.AllowNull.get()", ReflectData.AllowNull.get()) };
  }

  static final class NamedModel {
    final String name;
    final GenericData data;

    NamedModel(String name, GenericData data) {
      this.name = name;
      this.data = data;
    }
  }
}
