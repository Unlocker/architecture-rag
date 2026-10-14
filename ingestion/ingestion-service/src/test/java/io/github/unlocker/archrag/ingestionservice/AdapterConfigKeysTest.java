package io.github.unlocker.archrag.ingestionservice;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.sourcespi.SourceSystem;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/** Ключи {@code archrag.adapters.*} в application.yml — ровно коды {@link SourceSystem}. */
class AdapterConfigKeysTest {

  private static final String PREFIX = "archrag.adapters.";

  @Test
  void adapterKeysMatchSourceSystemCodes() {
    YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new ClassPathResource("application.yml"));
    Properties props = yaml.getObject();
    assertThat(props).isNotNull();

    Set<String> keys = new TreeSet<>();
    for (String name : props.stringPropertyNames()) {
      if (name.startsWith(PREFIX)) {
        keys.add(name.substring(PREFIX.length()).split("\\.", 2)[0]);
      }
    }

    assertThat(keys)
        .containsExactlyInAnyOrderElementsOf(Arrays.stream(SourceSystem.values()).map(SourceSystem::code).toList());
  }
}
