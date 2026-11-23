package com.medcollect;

import static com.medcollect.Records.*;
import static com.medcollect.Support.bad;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

record CaptureSchedule(ScheduleMode mode, int minutes, Long at, String dailyTime) {
    private static final ZoneId DAILY_ZONE = ZoneId.of("Asia/Shanghai");

    static CaptureSchedule of(TaskInput input) {
        ScheduleMode mode = mode(input.scheduleMode(), input.intervalMinutes());
        if (input.mode() == Mode.UPLOAD && mode != ScheduleMode.MANUAL) throw bad("手工上传无需设置定时");
        return switch (mode) {
            case MANUAL -> new CaptureSchedule(mode, 0, null, null);
            case INTERVAL -> {
                if (input.intervalMinutes() < 1) throw bad("请输入执行间隔");
                yield new CaptureSchedule(mode, input.intervalMinutes(), null, null);
            }
            case ONCE -> {
                if (input.scheduleAt() == null || input.scheduleAt() <= 0) throw bad("请选择单次执行时间");
                yield new CaptureSchedule(mode, 0, input.scheduleAt(), null);
            }
            case DAILY -> new CaptureSchedule(mode, 0, null, time(input.dailyTime()).toString());
        };
    }

    static Long afterScheduledRun(Task task, long now) {
        return new CaptureSchedule(mode(task.scheduleMode(), task.intervalMinutes()), task.intervalMinutes(),
                task.scheduleAt(), task.dailyTime()).next(now, true);
    }

    Long afterSave(Task previous, boolean enabled, long now) {
        boolean unchanged = previous != null && mode == mode(previous.scheduleMode(), previous.intervalMinutes())
                && minutes == previous.intervalMinutes() && Objects.equals(at, previous.scheduleAt())
                && Objects.equals(dailyTime, previous.dailyTime());
        if (enabled && mode == ScheduleMode.ONCE && at <= now && (!unchanged || !previous.enabled())) {
            throw bad("单次执行时间须晚于当前时间");
        }
        if (!enabled) return null;
        return unchanged && previous.enabled() ? previous.nextRunAt() : next(now, false);
    }

    private Long next(long now, boolean executed) {
        return switch (mode) {
            case MANUAL -> null;
            case INTERVAL -> now + minutes * 60_000L;
            case ONCE -> executed ? null : at;
            case DAILY -> {
                var local = Instant.ofEpochMilli(now).atZone(DAILY_ZONE);
                var day = local.toLocalDate().atTime(time(dailyTime)).atZone(DAILY_ZONE);
                yield (day.isAfter(local) ? day : day.plusDays(1)).toInstant().toEpochMilli();
            }
        };
    }

    private static ScheduleMode mode(ScheduleMode mode, int minutes) {
        return mode == null ? (minutes > 0 ? ScheduleMode.INTERVAL : ScheduleMode.MANUAL) : mode;
    }

    private static LocalTime time(String input) {
        if (input == null || !input.matches("\\d{2}:\\d{2}")) throw bad("请选择每日执行时间");
        try {
            return LocalTime.parse(input);
        } catch (DateTimeException e) {
            throw bad("每日执行时间无效");
        }
    }
}
