package cn.sky.jnic;

public class Main {

    public static void main(String[] args) {
        Thread.currentThread().setName("Main Thread");
        new Jnic();
    }
}