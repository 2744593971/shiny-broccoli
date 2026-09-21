package com.duli.exceptions;

/** 预期的商城业务失败，交由 API 层转换成项目统一 JSON 返回体。 */
public class ShopException extends RuntimeException {
    private final int code;
    public ShopException(int code, String message) { super(message); this.code = code; }
    public int getCode() { return code; }
}
