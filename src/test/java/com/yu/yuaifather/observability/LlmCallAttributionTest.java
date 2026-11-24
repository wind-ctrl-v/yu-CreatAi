package com.yu.yuaifather.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmCallAttributionTest {

    @AfterEach
    void clear() {
        assertNull(LlmCallAttribution.current());
    }

    @Test
    void nestedScopeRestoresPreviousAndDoesNotLeak() {
        try (var outer = LlmCallAttribution.open("gen_outer", 1L, "PlanningAgent")) {
            assertEquals("PlanningAgent", LlmCallAttribution.current().step());
            try (var inner = LlmCallAttribution.open("gen_inner", 2L, "CriticAgent")) {
                assertEquals("CriticAgent", LlmCallAttribution.current().step());
                assertEquals("gen_inner", LlmCallAttribution.current().generationId());
            }
            assertEquals("gen_outer", LlmCallAttribution.current().generationId());
            assertEquals("PlanningAgent", LlmCallAttribution.current().step());
        }
        assertNull(LlmCallAttribution.current());
    }

    @Test
    void blankStepIsLabeledUnattributed() {
        try (var ignored = LlmCallAttribution.open("gen_1", 3L, "  ")) {
            Map<Object, Object> attributes = new HashMap<>();
            LlmCallAttribution.stamp(attributes);
            LlmCallScope scope = LlmCallAttribution.fromAttributes(attributes);
            assertEquals("gen_1", scope.generationId());
            assertEquals(3L, scope.appId());
            assertEquals(LlmSteps.UNATTRIBUTED, scope.step());
        }
    }

    @Test
    void missingScopeIsStampedUnattributedInsteadOfDropped() {
        Map<Object, Object> attributes = new HashMap<>();
        LlmCallAttribution.stamp(attributes);
        LlmCallScope scope = LlmCallAttribution.fromAttributes(attributes);
        assertNull(scope.generationId());
        assertEquals(LlmSteps.UNATTRIBUTED, scope.step());
    }

    @Test
    void fromAttributesIgnoresAnythingExceptTheAttributeMap() {
        Map<Object, Object> attributes = new HashMap<>();
        attributes.put(LlmCallAttribution.ATTR_GENERATION_ID, "gen_attr");
        attributes.put(LlmCallAttribution.ATTR_APP_ID, 8L);
        attributes.put(LlmCallAttribution.ATTR_STEP, "CoderAgent");
        try (var ignored = LlmCallAttribution.open("gen_thread", 1L, "StyleAgent")) {
            LlmCallScope scope = LlmCallAttribution.fromAttributes(attributes);
            assertEquals("gen_attr", scope.generationId());
            assertEquals(8L, scope.appId());
            assertEquals("CoderAgent", scope.step());
        }
    }

    @Test
    void scopedFluxSeesAttributionOnlyDuringSubscribe() {
        AtomicReference<LlmCallScope> seen = new AtomicReference<>();
        Flux<String> inner = Flux.defer(() -> {
            seen.set(LlmCallAttribution.current());
            return Flux.just("ok");
        });
        assertEquals("ok", LlmCallAttribution.scopedFlux(inner, "gen_html", 4L, LlmSteps.CLASSIC_HTML)
                .blockLast());
        assertEquals("gen_html", seen.get().generationId());
        assertEquals(4L, seen.get().appId());
        assertEquals(LlmSteps.CLASSIC_HTML, seen.get().step());
        assertNull(LlmCallAttribution.current());
    }

    @Test
    void stampSurvivesAMapThatRejectsNulls() {
        Map<Object, Object> attributes = new HashMap<>();
        try (var ignored = LlmCallAttribution.open(null, null, "ClassicVue")) {
            LlmCallAttribution.stamp(attributes);
        }
        assertEquals(LlmSteps.CLASSIC_VUE, attributes.get(LlmCallAttribution.ATTR_STEP));
        assertNull(attributes.get(LlmCallAttribution.ATTR_GENERATION_ID));
    }

    @Test
    void brokenAttributeMapDoesNotEscapeStampWhenCallerCatches() {
        Map<Object, Object> attributes = new HashMap<>() {
            @Override
            public Object put(Object key, Object value) {
                throw new IllegalStateException("attributes unavailable");
            }
        };
        assertThrows(IllegalStateException.class, () -> LlmCallAttribution.stamp(attributes));
    }
}
