package com.stripe.mpp.server;

import com.stripe.mpp.Credential;
import com.stripe.mpp.Receipt;

import java.lang.reflect.Method;
import java.util.Map;

/** Selects split lifecycle hooks when both are implemented, with legacy verify fallback. */
final class IntentLifecycle {
    private static final ClassValue<Boolean> HAS_SPLIT_HOOKS = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return overrides(type, "validate") && overrides(type, "broadcast");
        }
    };

    private IntentLifecycle() {}

    static Receipt broadcast(
        Intent intent,
        Credential credential,
        Map<String, Object> request
    ) {
        if (HAS_SPLIT_HOOKS.get(intent.getClass())) {
            intent.validate(credential, request);
            return intent.broadcast(credential, request);
        }
        return intent.verify(credential, request);
    }

    private static boolean overrides(Class<?> type, String name) {
        try {
            Method method = type.getMethod(name, Credential.class, Map.class);
            return method.getDeclaringClass() != Intent.class;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Intent lifecycle method is missing", e);
        }
    }
}
