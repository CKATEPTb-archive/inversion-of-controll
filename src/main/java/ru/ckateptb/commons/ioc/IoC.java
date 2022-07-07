package ru.ckateptb.commons.ioc;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.google.common.reflect.ClassPath;
import ru.ckateptb.commons.ioc.annotation.*;
import ru.ckateptb.commons.ioc.exception.CircularDependenciesException;
import ru.ckateptb.commons.ioc.exception.IoCBeanNotFound;
import ru.ckateptb.commons.ioc.exception.IoCException;
import ru.ckateptb.commons.ioc.utils.FinderUtils;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class IoC {
    private final static Table<Class<?>, String, Object> beans = HashBasedTable.create();
    private final static Map<Class<?>, Class<?>> classImplementations = new HashMap<>();

    public static void scan(Object main, Object... beans) throws IOException, IoCBeanNotFound, CircularDependenciesException {
        scan(main, packageName -> {
            System.out.println(packageName);
            return true;
        }, beans);
    }

    public static void scan(Object main, Predicate<String> filter, Object... beans) throws IOException, IoCBeanNotFound, CircularDependenciesException {
        Class<?> mainClass = main.getClass();
        register(mainClass);
        for (Object bean : beans) register(bean);
        ComponentScan componentScan = mainClass.getAnnotation(ComponentScan.class);
        String[] packages = componentScan == null ? new String[]{mainClass.getPackage().getName()} : componentScan.value();
        for (String packageName : packages) {
            List<Class<?>> classes = new ArrayList<>();
            for (ClassPath.ClassInfo classInfo : ClassPath.from(mainClass.getClassLoader()).getTopLevelClassesRecursive(packageName)) {
                if (!filter.test(classInfo.getName())) continue;
                Class<?> clazz = classInfo.load();
                if (!clazz.isAnnotationPresent(Component.class)) continue;
                registerImplementation(clazz);
                classes.add(clazz);
            }
            List<BeanProcessor> processors = new ArrayList<>();
            for (Class<?> clazz : classes) {
                processors.add(new BeanProcessor(clazz));
            }
            for (BeanProcessor processor : processors) {
                Class<?> clazz = processor.getClazz();
                try {
                    Object value = processor.register();
                    FinderUtils.findFields(clazz, Autowired.class).forEach(field -> {
                        String qualifier = null;
                        if (field.isAnnotationPresent(Qualifier.class)) {
                            qualifier = field.getAnnotation(Qualifier.class).value();
                        }
                        try {
                            Object fieldValue = new BeanProcessor(field.getType(), qualifier).register();
                            field.set(value, fieldValue);
                        } catch (CircularDependenciesException | IllegalAccessException | IoCBeanNotFound e) {
                            throw new IoCException(e);
                        }
                    });
                    FinderUtils.findMethods(clazz, PostConstruct.class).forEach(post -> {
                        if (post.getParameterCount() > 0) {
                            new IoCException("Method " + post + " must not take parameters").printStackTrace();
                        } else {
                            try {
                                post.invoke(value);
                            } catch (IllegalAccessException | InvocationTargetException e) {
                                throw new IoCException(e);
                            }
                        }
                    });
                } catch (ArrayIndexOutOfBoundsException e) {
                    throw new IoCException(e);
                }
            }
        }
    }

    private static void registerImplementation(Class<?> clazz) {
        classImplementations.put(clazz, clazz);
        Class<?> superClass = clazz.getSuperclass();
        if (!superClass.getName().equals("java.lang.Object")) {
            classImplementations.put(superClass, clazz);
            registerImplementation(superClass);
        }
        for (Class<?> interfaceClazz : clazz.getInterfaces()) {
            classImplementations.put(interfaceClazz, clazz);
        }
    }

    public static void register(Object value) {
        register(value.getClass(), value);
    }

    public static void register(Class<?> clazz, Object value) {
        register(clazz, clazz.getName(), value);
    }

    public static void register(Class<?> clazz, String name, Object value) {
        beans.put(clazz, name, value);
    }

    public static <T> T get(Class<T> clazz) {
        return get(clazz, clazz.getName());
    }

    public static <T> T get(Class<T> clazz, String name) {
        return clazz.cast(beans.get(clazz, name));
    }

    public static boolean has(Class<?> clazz) {
        return has(clazz, clazz.getName());
    }

    public static boolean has(Class<?> clazz, String name) {
        return beans.contains(clazz, name);
    }

    static Class<?> getClassImplementation(Class<?> clazz) {
        if (!classImplementations.containsKey(clazz)) {
            throw new IoCException("No implementation found for interface " + clazz.getName());
        }
        return classImplementations.get(clazz);
    }
}
