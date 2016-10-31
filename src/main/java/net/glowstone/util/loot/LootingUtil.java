package net.glowstone.util.loot;

import org.bukkit.entity.LivingEntity;

import java.util.Objects;

public class LootingUtil {

    public static boolean is(Object a, Object b) {
        return Objects.equals(a, b);
    }

    public static boolean and(boolean a, boolean b) {
        return a && b;
    }

    public static boolean or(boolean a, boolean b) {
        return a || b;
    }

    public static boolean not(boolean b) {
        return !b;
    }

    public static boolean conditionValue(LivingEntity entity, String condition) {
        if (condition.equals("ENTITY_ONFIRE")) {
            return entity.getFireTicks() > 0;
        }
        // todo: more conditions, reflection
        return false;
    }
}
