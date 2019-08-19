package io.netty.example.server.shutdownhook;

import java.util.concurrent.TimeUnit;

/**
 * 程序正常退出的情况
 * 当main线程运行结束之后就会调用关闭钩子
 */
public class HookTest1 {

    public void start() {
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                System.out.println("Execute Hook.....");
            }
        }));
    }

    public static void main(String[] args) {
        new HookTest1().start();
        System.out.println("The Application is doing something");

        try {
            TimeUnit.MILLISECONDS.sleep(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}