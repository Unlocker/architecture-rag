package io.github.unlocker.archrag.demolauncher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.unlocker.archrag.eamadapter.EamAdapter;
import io.github.unlocker.archrag.eamadapter.EamApiConnector;
import io.github.unlocker.archrag.sourcestubs.EamApiSeed;
import io.github.unlocker.archrag.sourcestubs.EamApiStub;
import java.time.Clock;
import org.junit.jupiter.api.Test;

/** Типы стенда (заглушка и control) согласованы с типами, которые читает коннектор; проверка подключения проходит на заглушке. */
class EamStandTypesTest {

  @Test
  void solutionTypeOfStandEqualsConnectorAssumption() {
    assertThat(EamApiSeed.SOLUTION_TYPE).isEqualTo(EamApiConnector.SOLUTION_TYPE);
  }

  @Test
  void standTypesEqualTypesReadByConnector() {
    assertThat(EamApiSeed.TYPES).isEqualTo(EamApiConnector.READ_TYPES);
  }

  @Test
  void startAgainstStubPassesAccessCheck() {
    try (var stub = new EamApiStub(EamApiSeed.seeded(Clock.systemUTC()), "tok")) {
      assertThatCode(() -> DemoLauncher.verifyEamAccess(new EamAdapter.EamApi(stub.baseUri(), "tok")))
          .doesNotThrowAnyException();
    }
  }

  @Test
  void startWithWrongTokenFailsClosedWithoutLeakingIt() {
    try (var stub = new EamApiStub(EamApiSeed.seeded(Clock.systemUTC()), "tok")) {
      assertThatThrownBy(() -> DemoLauncher.verifyEamAccess(new EamAdapter.EamApi(stub.baseUri(), "bad-token")))
          .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("bad-token");
    }
  }
}
