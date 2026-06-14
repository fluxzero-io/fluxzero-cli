package io.fluxzero.examples;

import org.apache.commons.lang3.StringUtils;

public class PlainApplication {
    public static void main(String[] args) {
        System.out.println(message(args.length == 0 ? "Fluxzero" : args[0]));
    }

    public static String message(String name) {
        return "Hello " + StringUtils.capitalize(name);
    }
}
