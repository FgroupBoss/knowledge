package com.acme.kb.p0.config;

import com.acme.kb.p0.scope.FixtureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 空库时建立引导租户与 OWNER，使验证脚本可以通过 HTTP 夹具构造其余样本。
 * 只在夹具开启时生效，生产形态不创建任何身份。
 */
@Component
@ConditionalOnProperty(prefix = "p0.fixture", name = "enabled", havingValue = "true")
public class BaselineBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BaselineBootstrap.class);

    private final FixtureService fixture;
    private final P0Properties props;

    public BaselineBootstrap(FixtureService fixture, P0Properties props) {
        this.fixture = fixture;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean created = fixture.bootstrapIfEmpty(props.fixture().baselineTenant(),
                props.fixture().baselineAdmin());
        if (created) {
            log.info("baseline_bootstrap tenant={} admin={}", props.fixture().baselineTenant(),
                    props.fixture().baselineAdmin());
        }
    }
}
