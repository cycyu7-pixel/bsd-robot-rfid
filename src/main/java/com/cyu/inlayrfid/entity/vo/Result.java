package com.cyu.inlayrfid.entity.vo;

/**
 * 接口统一响应对象。
 *
 * @param <T> 响应数据类型
 */
public class Result<T> {

    /**
     * 是否成功。
     */
    private boolean success;

    /**
     * 提示信息。
     */
    private String message;

    /**
     * 响应数据。
     */
    private T data;

    public Result() {
    }

    public Result(boolean success, String message, T data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> success() {
        return new Result<>(true, "操作成功", null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(true, "操作成功", data);
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<>(true, message, data);
    }

    public static <T> Result<T> fail(String message) {
        return new Result<>(false, message, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}