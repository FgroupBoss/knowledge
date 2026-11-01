package com.acme.kb.p0.auth;

import com.acme.kb.p0.common.ApiException;
import com.acme.kb.p0.common.ErrorCode;

/**
 * 请求级主体上下文。仅在 servlet 线程内有效；异步/流式路径必须显式传递 Actor，
 * 不允许在 worker 线程里隐式读取。
 */
public final class ActorHolder {

    private static final ThreadLocal<Actor> CURRENT = new ThreadLocal<>();

    private ActorHolder() {
    }

    static void set(Actor actor) {
        CURRENT.set(actor);
    }

    static void clear() {
        CURRENT.remove();
    }

    public static Actor require() {
        Actor a = CURRENT.get();
        if (a == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "缺少有效身份");
        }
        return a;
    }

    public static Actor currentOrNull() {
        return CURRENT.get();
    }
}
