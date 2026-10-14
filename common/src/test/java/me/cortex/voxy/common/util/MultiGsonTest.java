package me.cortex.voxy.common.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MultiGsonTest {

    public static final class Foo {
        public int a;
        public int b;
    }

    public static final class Bar {
        public int x;
        public String y;
    }

    public static final class FooConflict {
        public int a;
    }

    @Test
    void roundTripPreservesValues() {
        var gson = new MultiGson.Builder().add(Foo.class).add(Bar.class).build();
        var foo = new Foo(); foo.a = 7; foo.b = 11;
        var bar = new Bar(); bar.x = -3; bar.y = "hello";

        String json = gson.toJson(foo, bar);
        Map<Class<?>, Object> back = gson.fromJson(json);

        assertEquals(7, ((Foo) back.get(Foo.class)).a);
        assertEquals(11, ((Foo) back.get(Foo.class)).b);
        assertEquals(-3, ((Bar) back.get(Bar.class)).x);
        assertEquals("hello", ((Bar) back.get(Bar.class)).y);
    }

    @Test
    void wrongArgCountRejected() {
        var gson = new MultiGson.Builder().add(Foo.class).add(Bar.class).build();
        assertThrows(IllegalArgumentException.class, () -> gson.toJson(new Foo()));
    }

    @Test
    void unknownClassRejected() {
        var gson = new MultiGson.Builder().add(Foo.class).build();
        assertThrows(IllegalArgumentException.class, () -> gson.toJson(new Bar()));
    }

    @Test
    void nullArgRejected() {
        var gson = new MultiGson.Builder().add(Foo.class).build();
        assertThrows(IllegalArgumentException.class, () -> gson.toJson((Object) null));
    }

    @Test
    void duplicateClassInBuilderRejected() {
        var b = new MultiGson.Builder().add(Foo.class);
        assertThrows(IllegalArgumentException.class, () -> b.add(Foo.class));
    }

    @Test
    void duplicateFieldNameAcrossClassesRejected() {
        // Foo and FooConflict both expose field 'a' → toJson must reject the merge.
        var gson = new MultiGson.Builder().add(Foo.class).add(FooConflict.class).build();
        assertThrows(IllegalArgumentException.class, () -> gson.toJson(new Foo(), new FooConflict()));
    }

    @Test
    void fromJsonReturnsEntryPerRegisteredClass() {
        var gson = new MultiGson.Builder().add(Foo.class).add(Bar.class).build();
        var json = gson.toJson(new Foo(), new Bar());
        Map<Class<?>, Object> back = gson.fromJson(json);
        assertEquals(2, back.size());
        assertNotNull(back.get(Foo.class));
        assertNotNull(back.get(Bar.class));
    }
}
