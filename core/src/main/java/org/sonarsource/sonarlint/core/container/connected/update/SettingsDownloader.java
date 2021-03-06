/*
 * SonarLint Core - Implementation
 * Copyright (C) 2009-2019 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonarsource.sonarlint.core.container.connected.update;

import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonarqube.ws.Settings.FieldValues.Value;
import org.sonarqube.ws.Settings.Setting;
import org.sonarqube.ws.Settings.ValuesWsResponse;
import org.sonarsource.sonarlint.core.container.connected.SonarLintWsClient;
import org.sonarsource.sonarlint.core.container.storage.ProtobufUtil;
import org.sonarsource.sonarlint.core.container.storage.StoragePaths;
import org.sonarsource.sonarlint.core.plugin.Version;
import org.sonarsource.sonarlint.core.proto.Sonarlint.GlobalProperties;
import org.sonarsource.sonarlint.core.proto.Sonarlint.ProjectConfiguration;
import org.sonarsource.sonarlint.core.util.StringUtils;

import static java.util.stream.Collectors.joining;

public class SettingsDownloader {
  private static final Logger LOG = Loggers.get(SettingsDownloader.class);

  private static final String API_SETTINGS_PATH = "/api/settings/values.protobuf";
  private static final String API_PROPERTIES_PATH = "/api/properties?format=json";
  private final SonarLintWsClient wsClient;

  public SettingsDownloader(SonarLintWsClient wsClient) {
    this.wsClient = wsClient;
  }

  public void fetchGlobalSettingsTo(Version serverVersion, Path dest) {
    ProtobufUtil.writeToFile(fetchGlobalSettings(serverVersion), dest.resolve(StoragePaths.PROPERTIES_PB));
  }

  public GlobalProperties fetchGlobalSettings(Version serverVersion) {
    GlobalProperties.Builder builder = GlobalProperties.newBuilder();
    fetchSettings(serverVersion, null, (k, v) -> true, builder::putProperties);
    return builder.build();
  }

  public void fetchProjectSettings(Version serverVersion, String projectKey, GlobalProperties globalProps, ProjectConfiguration.Builder projectConfigurationBuilder) {
    fetchSettings(serverVersion, projectKey, (k, v) -> !v.equals(globalProps.getPropertiesMap().get(k)), projectConfigurationBuilder::putProperties);
  }

  private void fetchSettings(Version serverVersion, @Nullable String projectKey, BiPredicate<String, String> filter, BiConsumer<String, String> consumer) {
    if (serverVersion.compareToIgnoreQualifier(Version.create("6.3")) >= 0) {
      fetchUsingSettingsWS(projectKey, consumer);
    } else {
      fetchUsingPropertiesWS(projectKey, filter, consumer);
    }
  }

  private void fetchUsingSettingsWS(@Nullable String projectKey, BiConsumer<String, String> consumer) {
    StringBuilder url = new StringBuilder();
    url.append(API_SETTINGS_PATH);
    if (projectKey != null) {
      url.append("?component=").append(StringUtils.urlEncode(projectKey));
    }
    SonarLintWsClient.consumeTimed(
      () -> wsClient.get(url.toString()),
      response -> {
        try (InputStream is = response.contentStream()) {
          ValuesWsResponse values = ValuesWsResponse.parseFrom(is);
          for (Setting s : values.getSettingsList()) {
            // Storage optimisation: don't store settings having same value than global settings
            if (!s.getInherited()) {
              processSetting(consumer, s);
            }
          }
        } catch (IOException e) {
          throw new IllegalStateException("Unable to parse properties from: " + response.content(), e);
        }
      },
      duration -> LOG.info("Downloaded settings in {}ms", duration));
  }

  private static void processSetting(BiConsumer<String, String> consumer, Setting s) {
    switch (s.getValueOneOfCase()) {
      case VALUE:
        consumer.accept(s.getKey(), s.getValue());
        break;
      case VALUES:
        consumer.accept(s.getKey(), s.getValues().getValuesList().stream().collect(joining(",")));
        break;
      case FIELDVALUES:
        processPropertySet(s, consumer);
        break;
      default:
        throw new IllegalStateException("Unknow property value for " + s.getKey());
    }
  }

  private static void processPropertySet(Setting s, BiConsumer<String, String> consumer) {
    List<String> ids = new ArrayList<>();
    int id = 1;
    for (Value v : s.getFieldValues().getFieldValuesList()) {
      for (Map.Entry<String, String> entry : v.getValue().entrySet()) {
        consumer.accept(s.getKey() + "." + id + "." + entry.getKey(), entry.getValue());
      }
      ids.add(String.valueOf(id));
      id++;
    }
    consumer.accept(s.getKey(), ids.stream().collect(Collectors.joining(",")));
  }

  private void fetchUsingPropertiesWS(@Nullable String projectKey, BiPredicate<String, String> filter, BiConsumer<String, String> consumer) {
    StringBuilder url = new StringBuilder();
    url.append(API_PROPERTIES_PATH);
    if (projectKey != null) {
      url.append("&resource=").append(StringUtils.urlEncode(projectKey));
    }
    SonarLintWsClient.consumeTimed(
      () -> wsClient.get(url.toString()),
      response -> {
        try (JsonReader reader = new JsonReader(response.contentReader())) {
          reader.beginArray();
          while (reader.hasNext()) {
            reader.beginObject();
            parseProperty(filter, consumer, reader);
            reader.endObject();
          }
          reader.endArray();
        } catch (IOException e) {
          throw new IllegalStateException("Unable to parse properties from: " + response.content(), e);
        }
      },
      duration -> LOG.debug("Downloaded settings in {}ms", duration));
  }

  private static void parseProperty(BiPredicate<String, String> filter, BiConsumer<String, String> consumer, JsonReader reader) throws IOException {
    String key = null;
    String value = null;
    while (reader.hasNext()) {
      String propName = reader.nextName();
      switch (propName) {
        case "key":
          key = reader.nextString();
          break;
        case "value":
          value = reader.nextString();
          break;
        default:
          reader.skipValue();
      }
    }
    // Storage optimisation: don't store properties having same value than global properties
    if (filter.test(key, value)) {
      consumer.accept(key, value);
    }
  }

}
