package bw.status.handler.routing;

import io.undertow.server.handlers.DisableCacheHandler;
import jakarta.inject.Qualifier;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that responses from an HTTP handler must not be cached by clients.
 * Similar to {@link DisableCacheHandler}.
 *
 * <p>This annotation may only be applied to services that are annotated with at
 * least one {@link Route}.
 */
@Qualifier
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({
    ElementType.TYPE,
    ElementType.METHOD,
    ElementType.FIELD,
    ElementType.PARAMETER
})
public @interface DisableCache {}
