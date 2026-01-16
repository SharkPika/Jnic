package cn.sky.jnic.utils;

import java.util.regex.Pattern;

public class MatcherUtils {

    public static boolean match(String text, String pattern) {
        if (pattern.equals("*")) return true;
        
        // Convert Ant-style glob pattern to Regex
        // cn/** -> cn/.*
        // cn/*.Class -> cn/[^/]*\.Class
        
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                        regex.append(".*");
                        i++;
                    } else {
                        regex.append("[^/]*");
                    }
                }
                case '?' -> regex.append(".");
                case '.' -> regex.append("\\.");
                case '/' -> regex.append("/");
                default -> {
                    if (Character.isLetterOrDigit(c)) {
                        regex.append(c);
                    } else {
                        regex.append("\\").append(c);
                    }
                }
            }
        }
        regex.append("$");
        
        return Pattern.matches(regex.toString(), text);
    }
}
