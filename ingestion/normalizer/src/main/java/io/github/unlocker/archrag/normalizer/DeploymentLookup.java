package io.github.unlocker.archrag.normalizer;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import java.util.List;
import java.util.Optional;

/**
 * Порт чтения графа для привязки {@code Deployment} к репозиторию и сервису. Реализация (проекция Neo4j)
 * подключается снаружи; normalizer хранилищ не знает. Отдельно от {@link ReferenceResolver}, который отвечает
 * только на вопрос «известен ли ключ».
 *
 * <p>Инварианты: читаются только активные записи SCM и открытые связи ({@code validTo IS NULL}); результат
 * — снимок на момент нормализации, переоценки при последующих изменениях SCM нет; значения из источника
 * не логируются.
 */
public interface DeploymentLookup {

  /** Порт без данных: ничего не находит. */
  DeploymentLookup NONE =
      new DeploymentLookup() {
        @Override
        public Optional<SourceKey> repositoryByUrl(String normalizedUrl) {
          return Optional.empty();
        }

        @Override
        public List<SourceKey> servicesImplementedIn(SourceKey repository) {
          return List.of();
        }
      };

  /**
   * Активная запись SCM {@code REPOSITORY}, чей {@code GitUrls.normalize(url)} равен аргументу.
   *
   * @param normalizedUrl URL в каноническом виде ({@code GitUrls.normalize})
   */
  Optional<SourceKey> repositoryByUrl(String normalizedUrl);

  /** Активные записи SCM {@code SERVICE} с открытой {@code IMPLEMENTED_IN} на репозиторий {@code repository}. */
  List<SourceKey> servicesImplementedIn(SourceKey repository);
}
