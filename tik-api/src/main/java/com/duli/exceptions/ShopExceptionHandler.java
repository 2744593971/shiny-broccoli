package com.duli.exceptions;

import com.duli.controller.ShopController;
import com.duli.controller.ShopTradeController;

import com.duli.grace.result.GraceJSONResult;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.transaction.TransactionException;

/** 错误仍保持 status/msg/success/data 格式，客户端可直接复用项目返回协议。 */
@RestControllerAdvice(assignableTypes = {ShopController.class,ShopTradeController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ShopExceptionHandler {
    // 商城单独返回明确的 400，不受旧模块“校验失败仍 HTTP 200”协议影响。
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<GraceJSONResult> invalid(Exception error) {
        return response(400, "请求参数不正确，请检查商品、活动和请求编号");
    }
    @ExceptionHandler(TransactionException.class)
    public ResponseEntity<GraceJSONResult> transaction(TransactionException error) {
        return response(503, "订单结果暂无法确认，请使用原请求编号重试或查看我的订单");
    }
    @ExceptionHandler(ShopException.class)
    public ResponseEntity<GraceJSONResult> business(ShopException error) {
        return response(error.getCode(), error.getMessage());
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<GraceJSONResult> database(DataAccessException error) {
        // 不把 SQL、连接串或数据库异常详情暴露给客户端。
        return response(503, "商城服务暂不可用，请稍后重试；首次运行请先导入商城 SQL");
    }
    private ResponseEntity<GraceJSONResult> response(int code, String message) {
        GraceJSONResult result = GraceJSONResult.errorMsg(message);
        result.setStatus(code);
        return ResponseEntity.status(code).body(result);
    }
}
