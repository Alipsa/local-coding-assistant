/*
 * Copyright 2024-2025 Embabel Software, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package se.alipsa.lca;

import com.embabel.agent.config.annotation.EnableAgents;
import com.embabel.agent.config.annotation.LoggingThemes
import groovy.transform.CompileStatic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationListener
import org.springframework.core.env.ConfigurableEnvironment

@SpringBootApplication
@EnableAgents(loggingTheme = LoggingThemes.STAR_WARS)
@CompileStatic
class LocalCodingAssistantApplication {

    static void main(String[] args) {
        SpringApplication app = new SpringApplication(LocalCodingAssistantApplication)
        // Spring Boot forces java.awt.headless=true by default, which makes Swing throw
        // HeadlessException. Wait until the environment is fully prepared (system properties,
        // env vars, application.properties, profiles — the same sources GuiRunner's and
        // SwingConfirmationService's @ConditionalOnProperty(lca.gui.enabled) read) before
        // deciding, so this check can't disagree with theirs.
        //
        // Registered against the broad ApplicationEvent type and filtered manually below: Spring
        // resolves which specific event type a listener wants via reflection on its declared
        // generic type, which a Groovy-closure-coerced ApplicationListener doesn't expose, so a
        // listener declared for ApplicationEnvironmentPreparedEvent specifically would otherwise
        // still receive earlier events (e.g. ApplicationStartingEvent) and blow up.
        app.addListeners({ ApplicationEvent event ->
            if (event instanceof ApplicationEnvironmentPreparedEvent) {
                configureHeadless(((ApplicationEnvironmentPreparedEvent) event).environment)
            }
        } as ApplicationListener<ApplicationEvent>)
        app.run(args)
    }

    static void configureHeadless(ConfigurableEnvironment environment) {
        if (environment.getProperty("lca.gui.enabled", Boolean, false)) {
            System.setProperty("java.awt.headless", "false")
        }
    }
}