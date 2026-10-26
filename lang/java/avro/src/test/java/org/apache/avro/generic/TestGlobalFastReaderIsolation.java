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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.avro.generic.FastReaderIsolationExtension.NamedModel;
import org.apache.avro.reflect.ReflectData;
import org.apache.avro.specific.SpecificData;
import org.junit.jupiter.api.Test;

/**
 * Guards the two-pass {@code org.apache.avro.fastread} Surefire configuration
 * (AVRO-4326). Default models must follow the JVM property, and tests must not
 * call {@code setFastReaderEnabled} on those singletons.
 */
public class TestGlobalFastReaderIsolation {

  private static final Pattern DIRECT_SINGLETON_MUTATION = Pattern.compile(
      "(GenericData|SpecificData|ReflectData(?:\\.AllowNull)?)\\.get\\s*\\(\\s*\\)\\s*\\.\\s*setFastReaderEnabled\\s*\\(");

  private static final Pattern SINGLETON_ASSIGNMENT = Pattern.compile(
      "(GenericData|SpecificData|ReflectData(?:\\.AllowNull)?)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?:GenericData|SpecificData|ReflectData(?:\\.AllowNull)?)\\.get\\s*\\(\\s*\\)");

  @Test
  void defaultModelsFollowSystemProperty() {
    boolean expected = FastReaderIsolationExtension.expectedFastReader();
    for (NamedModel model : FastReaderIsolationExtension.defaultModels()) {
      assertEquals(expected, model.data.isFastReaderEnabledFlag(), model.name);
    }
  }

  @Test
  void isolatedInstanceDoesNotChangeDefaults() {
    boolean expected = FastReaderIsolationExtension.expectedFastReader();

    GenericData isolatedGeneric = new GenericData();
    isolatedGeneric.setFastReaderEnabled(!expected);
    SpecificData isolatedSpecific = new SpecificData();
    isolatedSpecific.setFastReaderEnabled(!expected);
    ReflectData isolatedReflect = new ReflectData();
    isolatedReflect.setFastReaderEnabled(!expected);
    ReflectData.AllowNull isolatedAllowNull = new ReflectData.AllowNull();
    isolatedAllowNull.setFastReaderEnabled(!expected);

    assertEquals(!expected, isolatedGeneric.isFastReaderEnabledFlag());
    assertEquals(!expected, isolatedSpecific.isFastReaderEnabledFlag());
    assertEquals(!expected, isolatedReflect.isFastReaderEnabledFlag());
    assertEquals(!expected, isolatedAllowNull.isFastReaderEnabledFlag());
    for (NamedModel model : FastReaderIsolationExtension.defaultModels()) {
      assertEquals(expected, model.data.isFastReaderEnabledFlag(), model.name);
    }
  }

  @Test
  void testSourcesDoNotMutateDefaultFastReader() throws IOException {
    Path testRoot = testJavaRoot();
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(testRoot)) {
      List<Path> javaFiles = files.filter(path -> path.toString().endsWith(".java")).collect(Collectors.toList());
      for (Path javaFile : javaFiles) {
        String source = stripComments(new String(Files.readAllBytes(javaFile), StandardCharsets.UTF_8));
        Matcher direct = DIRECT_SINGLETON_MUTATION.matcher(source);
        while (direct.find()) {
          violations.add(rel(testRoot, javaFile) + ": " + direct.group());
        }
        Matcher assigned = SINGLETON_ASSIGNMENT.matcher(source);
        while (assigned.find()) {
          String identifier = assigned.group(2);
          Pattern use = Pattern.compile("\\b" + identifier + "\\s*\\.\\s*setFastReaderEnabled\\s*\\(");
          if (use.matcher(source).find()) {
            violations.add(rel(testRoot, javaFile) + ": " + assigned.group() + " then setFastReaderEnabled");
          }
        }
      }
    }
    assertTrue(violations.isEmpty(), "Tests must not change fast-reader on GenericData/SpecificData/ReflectData.get(); "
        + "use a dedicated instance (AVRO-4326):\n" + String.join("\n", violations));
  }

  @Test
  void expectedFastReaderMatchesGenericDataInitializer() {
    boolean fromProperty = "true".equalsIgnoreCase(System.getProperty(GenericData.FAST_READER_PROP, "true"));
    assertEquals(fromProperty, FastReaderIsolationExtension.expectedFastReader());
    // A newly constructed model copies the JVM property; the defaults must match.
    assertEquals(fromProperty, new GenericData().isFastReaderEnabledFlag());
    assertEquals(fromProperty, GenericData.get().isFastReaderEnabledFlag());
  }

  @Test
  void localFalseAndTrueSettingsStayLocal() {
    GenericData enabled = new GenericData();
    enabled.setFastReaderEnabled(true);
    GenericData disabled = new GenericData();
    disabled.setFastReaderEnabled(false);
    assertTrue(enabled.isFastReaderEnabledFlag());
    assertFalse(disabled.isFastReaderEnabledFlag());
    assertEquals(FastReaderIsolationExtension.expectedFastReader(), GenericData.get().isFastReaderEnabledFlag());
    assertEquals(FastReaderIsolationExtension.expectedFastReader(), SpecificData.get().isFastReaderEnabledFlag());
  }

  private static Path testJavaRoot() {
    String basedir = System.getProperty("basedir");
    if (basedir != null) {
      Path fromBasedir = Path.of(basedir, "src", "test", "java");
      if (Files.isDirectory(fromBasedir)) {
        return fromBasedir;
      }
    }
    Path fromCwd = Path.of("src", "test", "java");
    if (Files.isDirectory(fromCwd)) {
      return fromCwd;
    }
    throw new AssertionError(
        "Could not locate src/test/java (basedir=" + basedir + ", user.dir=" + System.getProperty("user.dir") + ")");
  }

  private static String rel(Path root, Path file) {
    return root.relativize(file).toString().replace('\\', '/');
  }

  private static String stripComments(String source) {
    source = source.replaceAll("(?s)/\\*.*?\\*/", " ");
    return source.replaceAll("//.*", " ");
  }
}
