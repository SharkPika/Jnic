package cn.sky.jnic.utils.time;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record Duration(long value) {
    public static Duration fromString(String source) {
        if (source.equalsIgnoreCase("perm") || source.equalsIgnoreCase("permanent")) {
            return new Duration(Integer.MAX_VALUE);
        }

        long totalTime = 0L;
        boolean found = false;
        Matcher matcher = Pattern.compile("\\d+\\D+").matcher(source);

        while (matcher.find()) {
            String s = matcher.group();
            long value = Long.parseLong(s.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)")[0]);
            String type = s.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)")[1];

            switch (type) {
                case "s" -> {
                    totalTime += value;
                    found = true;
                }
                case "m" -> {
                    totalTime += value * 60;
                    found = true;
                }
                case "h" -> {
                    totalTime += value * 60 * 60;
                    found = true;
                }
                case "d" -> {
                    totalTime += value * 60 * 60 * 24;
                    found = true;
                }
                case "w" -> {
                    totalTime += value * 60 * 60 * 24 * 7;
                    found = true;
                }
                case "M" -> {
                    totalTime += value * 60 * 60 * 24 * 30;
                    found = true;
                }
                case "y" -> {
                    totalTime += value * 60 * 60 * 24 * 365;
                    found = true;
                }
            }
        }

        return new Duration(!found ? -1 : totalTime * 1000);
    }
}
