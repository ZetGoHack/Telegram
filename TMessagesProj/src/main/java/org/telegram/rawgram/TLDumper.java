package org.telegram.rawgram;

import android.util.Base64;

import org.telegram.tgnet.TLObject;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection based dumper of TL objects into a JSON-like tree.
 * Abstract TL base classes hold the union of fields of all constructors, so
 * null fields are skipped to keep only what the concrete constructor carries.
 */
public class TLDumper {

    private static final int MAX_DEPTH = 32;
    private static final int MAX_BYTES = 256;
    private static final Map<Class<?>, Field[]> fieldsCache = new ConcurrentHashMap<>();

    public static Object toTree(Object object) {
        return toTree(object, 0, new IdentityHashMap<>());
    }

    public static String toJson(Object object) {
        StringBuilder sb = new StringBuilder();
        writeJson(sb, toTree(object), 0);
        return sb.toString();
    }

    /** One "path = value" line per leaf, easier to read than JSON. */
    public static String toFields(Object object) {
        StringBuilder sb = new StringBuilder();
        writeFields(sb, "", toTree(object));
        return sb.toString();
    }

    public static String typeName(Object object) {
        return object == null ? "null" : object.getClass().getSimpleName();
    }

    private static Object toTree(Object value, int depth, IdentityHashMap<Object, Boolean> path) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            String encoded = Base64.encodeToString(bytes, 0, Math.min(bytes.length, MAX_BYTES), Base64.NO_WRAP);
            return "bytes[" + bytes.length + "]:" + encoded + (bytes.length > MAX_BYTES ? "…" : "");
        }
        if (value instanceof Enum || value instanceof CharSequence || value instanceof Character) {
            return value.toString();
        }
        if (depth > MAX_DEPTH || path.containsKey(value)) {
            return "<" + typeName(value) + " …>";
        }
        path.put(value, true);
        try {
            if (value instanceof List) {
                List<?> list = (List<?>) value;
                ArrayList<Object> out = new ArrayList<>(list.size());
                for (int i = 0; i < list.size(); i++) {
                    out.add(toTree(list.get(i), depth + 1, path));
                }
                return out;
            }
            if (value instanceof Map) {
                LinkedHashMap<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                    out.put(String.valueOf(e.getKey()), toTree(e.getValue(), depth + 1, path));
                }
                return out;
            }
            if (value instanceof TLObject || value.getClass().getName().startsWith("org.telegram.tgnet")) {
                LinkedHashMap<String, Object> out = new LinkedHashMap<>();
                out.put("_", typeName(value));
                String constructor = constructorOf(value.getClass());
                if (constructor != null) {
                    out.put("_constructor", constructor);
                }
                for (Field field : fieldsOf(value.getClass())) {
                    Object fieldValue;
                    try {
                        fieldValue = field.get(value);
                    } catch (Throwable e) {
                        continue;
                    }
                    if (fieldValue == null) {
                        continue;
                    }
                    out.put(field.getName(), toTree(fieldValue, depth + 1, path));
                }
                return out;
            }
            return value.toString();
        } finally {
            path.remove(value);
        }
    }

    private static String constructorOf(Class<?> cls) {
        try {
            Field field = cls.getField("constructor");
            if (Modifier.isStatic(field.getModifiers())) {
                return String.format("0x%08x", field.getInt(null));
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    private static Field[] fieldsOf(Class<?> cls) {
        Field[] cached = fieldsCache.get(cls);
        if (cached != null) {
            return cached;
        }
        ArrayList<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != TLObject.class && c != Object.class; c = c.getSuperclass()) {
            hierarchy.add(0, c);
        }
        LinkedHashMap<String, Field> fields = new LinkedHashMap<>();
        for (Class<?> c : hierarchy) {
            for (Field field : c.getDeclaredFields()) {
                int mod = field.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || !Modifier.isPublic(mod) || field.isSynthetic()) {
                    continue;
                }
                fields.put(field.getName(), field);
            }
        }
        ArrayList<Field> ordered = new ArrayList<>(fields.size());
        Field flags = fields.remove("flags");
        if (flags != null) {
            ordered.add(flags);
        }
        Field flags2 = fields.remove("flags2");
        if (flags2 != null) {
            ordered.add(flags2);
        }
        ordered.addAll(fields.values());
        Field[] result = ordered.toArray(new Field[0]);
        fieldsCache.put(cls, result);
        return result;
    }

    private static void indent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) {
            sb.append("  ");
        }
    }

    @SuppressWarnings("unchecked")
    private static void writeJson(StringBuilder sb, Object node, int level) {
        if (node instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) node;
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                indent(sb, level + 1);
                writeString(sb, e.getKey());
                sb.append(": ");
                writeJson(sb, e.getValue(), level + 1);
                if (++i < map.size()) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            indent(sb, level);
            sb.append('}');
        } else if (node instanceof List) {
            List<Object> list = (List<Object>) node;
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                indent(sb, level + 1);
                writeJson(sb, list.get(i), level + 1);
                if (i + 1 < list.size()) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            indent(sb, level);
            sb.append(']');
        } else if (node instanceof String) {
            writeString(sb, (String) node);
        } else {
            sb.append(node);
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    @SuppressWarnings("unchecked")
    private static void writeFields(StringBuilder sb, String prefix, Object node) {
        if (node instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) node;
            Object type = map.get("_");
            if (type != null) {
                sb.append(prefix.isEmpty() ? "" : prefix).append(prefix.isEmpty() ? "" : " : ").append(type).append('\n');
            }
            for (Map.Entry<String, Object> e : map.entrySet()) {
                if ("_".equals(e.getKey()) || "_constructor".equals(e.getKey())) {
                    continue;
                }
                writeFields(sb, prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), e.getValue());
            }
        } else if (node instanceof List) {
            List<Object> list = (List<Object>) node;
            if (list.isEmpty()) {
                sb.append(prefix).append(" = []\n");
            }
            for (int i = 0; i < list.size(); i++) {
                writeFields(sb, prefix + "[" + i + "]", list.get(i));
            }
        } else {
            sb.append(prefix).append(" = ");
            if (node instanceof String) {
                writeString(sb, (String) node);
            } else {
                sb.append(node);
            }
            sb.append('\n');
        }
    }
}
