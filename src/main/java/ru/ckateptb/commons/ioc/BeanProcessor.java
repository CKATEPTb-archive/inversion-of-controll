package ru.ckateptb.commons.ioc;

import lombok.Getter;
import lombok.SneakyThrows;
import ru.ckateptb.commons.ioc.annotation.Autowired;
import ru.ckateptb.commons.ioc.annotation.Component;
import ru.ckateptb.commons.ioc.annotation.Qualifier;
import ru.ckateptb.commons.ioc.exception.CircularDependenciesException;
import ru.ckateptb.commons.ioc.exception.IoCBeanNotFound;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.*;

@Getter
public class BeanProcessor {
    private final Class<?> clazz;
    private final String qualifier;
    private Constructor<?> constructor;
    private final BeanProcessor[] constructorParameters;

    public BeanProcessor(Class<?> clazz) throws CircularDependenciesException, IoCBeanNotFound {
        this(clazz, null);
    }

    public BeanProcessor(Class<?> clazz, String qualifier, BeanProcessor... circularDetection) throws CircularDependenciesException, IoCBeanNotFound {
        this.clazz = IoC.getClassImplementation(clazz);
        if (qualifier != null) {
            this.qualifier = qualifier;
        } else {
            Qualifier annotation = this.clazz.getAnnotation(Qualifier.class);
            this.qualifier = annotation == null ? this.clazz.getName() : annotation.value();
        }
        List<BeanProcessor> circular = new ArrayList<>(List.of(circularDetection));
        if (circular.stream().anyMatch(beanProcessor -> beanProcessor.clazz.equals(this.clazz) && beanProcessor.qualifier.equals(qualifier))) {
            throw new CircularDependenciesException("Circular dependency detected when loading class " + this.clazz.getName());
        }
        circular.add(this);
        Constructor<?>[] declaredConstructors = this.clazz.getDeclaredConstructors();
        constructor = declaredConstructors[0];
        if (declaredConstructors.length > 1) {
            for (Constructor<?> value : declaredConstructors) {
                if (value.isAnnotationPresent(Autowired.class)) {
                    constructor = value;
                    break;
                }
            }
        }
        Annotation[][] parameterAnnotations = constructor.getParameterAnnotations();
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        List<BeanProcessor> list = new ArrayList<>();
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameterType = parameterTypes[i];
            String parameterQualifier = null;
            for (Annotation annotation : parameterAnnotations[i]) {
                if (annotation instanceof Qualifier annotationQualifier) {
                    parameterQualifier = annotationQualifier.value();
                    break;
                }
            }
            list.add(new BeanProcessor(parameterType, parameterQualifier, circular.toArray(BeanProcessor[]::new)));
        }
        this.constructorParameters = list.toArray(BeanProcessor[]::new);
    }
    @SneakyThrows
    public Object register() {
        if(IoC.has(clazz, qualifier)) return IoC.get(clazz, qualifier);
        constructor.setAccessible(true);
        Object value = constructor.newInstance(Arrays.stream(constructorParameters).map(BeanProcessor::register).toArray());
        IoC.register(clazz, qualifier, value);
        return value;
    }
}
