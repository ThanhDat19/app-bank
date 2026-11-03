package vn.banklab.core.reconciliation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import vn.banklab.core.reconciliation.infrastructure.ReconciliationScheduling;
import static org.assertj.core.api.Assertions.*;

class ReconciliationConfigurationTest {
    @Test void schedulerIsDisabledByDefault() {
        new ApplicationContextRunner().withUserConfiguration(ReconciliationScheduling.class)
                .run(context->{assertThat(context).hasNotFailed();assertThat(context).doesNotHaveBean(ReconciliationScheduling.class);});
    }
}
