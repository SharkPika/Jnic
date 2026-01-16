package cn.sky.jnic.utils.time;

import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TimeUtil {
    private static final String HOUR_FORMAT = "%02d:%02d:%02d";
    private static final String MINUTE_FORMAT = "%02d:%02d";
    private static final SimpleDateFormat TIME_TO_REQUEST_FORMAT = new SimpleDateFormat("MM-dd");
    public static String HOLIDAY_INFO = "";

    private TimeUtil() {
        throw new RuntimeException("Cannot instantiate a utility class.");
    }

    public static long getMinecraftDay(long mills) {
        return Math.floorDiv(mills, 2160000L);
    }

    public static long getMinecraftDay() {
        return getMinecraftDay(System.currentTimeMillis());
    }

    public static long getMinecraftTick(long mills) {
        long time = mills % 2160000L;
        double percent;
        if (time <= 1440000L) {
            percent = (double)time / 1440000.0;
        } else {
            percent = 1.0 + ((double)time - 1440000.0) / 720000.0;
        }

        return (long)(percent * 12000.0);
    }

    public static long getMinecraftTick() {
        return getMinecraftTick(System.currentTimeMillis());
    }

    public static Date getNextDayDate() {
        long daySpan = 86400000L;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd '00:00:00'");

        Date startTime;
        try {
            startTime = (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")).parse(sdf.format(System.currentTimeMillis()));
        } catch (ParseException e) {
            e.printStackTrace();
            return null;
        }

        if (System.currentTimeMillis() > startTime.getTime()) {
            startTime = new Date(startTime.getTime() + 86400000L);
        }

        return startTime;
    }


    public static Long getMillisecondNextEarlyMorning() {
        Calendar cal = Calendar.getInstance();
        cal.add(6, 1);
        cal.set(11, 0);
        cal.set(13, 0);
        cal.set(12, 0);
        cal.set(14, 0);
        return cal.getTimeInMillis() - System.currentTimeMillis();
    }

    public static String millisToTimer(long millis) {
        long seconds = millis / 1000L;
        return seconds > 3600L ? String.format("%02d:%02d:%02d", seconds / 3600L, seconds % 3600L / 60L, seconds % 60L) : String.format("%02d:%02d", seconds / 60L, seconds % 60L);
    }

    public static String millisToSeconds(long millis) {
        return (new DecimalFormat("#0.0")).format((double)((float)millis / 1000.0F));
    }

    public static String dateToString(Date date, String secondaryColor) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        return (new SimpleDateFormat("MMM dd yyyy " + (secondaryColor == null ? "" : secondaryColor) + "(hh:mm aa zz)")).format(date);
    }

    public static Timestamp addDuration(long duration) {
        return truncateTimestamp(new Timestamp(System.currentTimeMillis() + duration));
    }

    public static Timestamp truncateTimestamp(Timestamp timestamp) {
        if (timestamp.toLocalDateTime().getYear() > 2037) {
            timestamp.setYear(2037);
        }

        return timestamp;
    }

    public static Timestamp addDuration(Timestamp timestamp) {
        return truncateTimestamp(new Timestamp(System.currentTimeMillis() + timestamp.getTime()));
    }

    public static Timestamp fromMillis(long millis) {
        return new Timestamp(millis);
    }

    public static Timestamp getCurrentTimestamp() {
        return new Timestamp(System.currentTimeMillis());
    }

    public static String millisToRoundedTime(long millis) {
        ++millis;
        long seconds = millis / 1000L;
        long minutes = seconds / 60L;
        long hours = minutes / 60L;
        long days = hours / 24L;
        if (days > 0L) {
            return days + " 天 " + (hours - 24L * days) + " 小时";
        } else if (hours > 0L) {
            return hours + " 小时 " + (minutes - 60L * hours) + " 分钟";
        } else {
            return minutes > 0L ? minutes + " 分钟 " + (seconds - 60L * minutes) + " 秒" : seconds + " 秒";
        }
    }

    public static long parseTime(String time) {
        long totalTime = 0L;
        boolean found = false;
        Matcher matcher = Pattern.compile("\\d+\\D+").matcher(time);

        while(matcher.find()) {
            String s = matcher.group();
            Long value = Long.parseLong(s.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)")[0]);
            switch (s.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)")[1]) {
                case "s":
                    totalTime += value;
                    found = true;
                    break;
                case "m":
                    totalTime += value * 60L;
                    found = true;
                    break;
                case "h":
                    totalTime += value * 60L * 60L;
                    found = true;
                    break;
                case "d":
                    totalTime += value * 60L * 60L * 24L;
                    found = true;
                    break;
                case "w":
                    totalTime += value * 60L * 60L * 24L * 7L;
                    found = true;
                    break;
                case "M":
                    totalTime += value * 60L * 60L * 24L * 30L;
                    found = true;
                    break;
                case "y":
                    totalTime += value * 60L * 60L * 24L * 365L;
                    found = true;
            }
        }

        return !found ? -1L : totalTime * 1000L;
    }
}
