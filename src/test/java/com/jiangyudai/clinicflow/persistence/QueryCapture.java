package com.jiangyudai.clinicflow.persistence;

import org.springframework.beans.factory.config.BeanPostProcessor;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;

/** Captures this test thread's real prepared SELECTs and bindings for counting and EXPLAIN replay. */
final class QueryCapture implements BeanPostProcessor {
    private static final ThreadLocal<List<BoundQuery>> QUERIES = new ThreadLocal<>();

    record Observation<T>(T result, List<BoundQuery> queries) { }

    record Binding(Method method, Object[] arguments) { }

    record BoundQuery(String sql, List<Binding> bindings) {
        String explain(Connection connection) throws Exception {
            try (PreparedStatement statement = connection.prepareStatement(
                    "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql)) {
                for (Binding binding : bindings) {
                    invoke(binding.method(), statement, binding.arguments());
                }
                try (var result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new SQLException("EXPLAIN returned no plan");
                    }
                    return result.getString(1);
                }
            }
        }
    }

    static <T> Observation<T> observe(Supplier<T> action) {
        if (QUERIES.get() != null) {
            throw new IllegalStateException("Nested query capture is not supported");
        }
        var queries = new ArrayList<BoundQuery>();
        QUERIES.set(queries);
        try {
            return new Observation<>(action.get(), List.copyOf(queries));
        } finally {
            QUERIES.remove();
        }
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        if (!(bean instanceof DataSource source)) {
            return bean;
        }
        return Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> {
                    Object result = invoke(method, source, arguments);
                    if (result instanceof Connection connection && method.getName().equals("getConnection")
                            && QUERIES.get() != null) {
                        return connection(connection);
                    }
                    return result;
                });
    }

    private static Connection connection(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    Object result = invoke(method, delegate, arguments);
                    if (result instanceof PreparedStatement statement && method.getName().equals("prepareStatement")) {
                        return statement(statement, (String) arguments[0]);
                    }
                    return result;
                });
    }

    private static PreparedStatement statement(PreparedStatement delegate, String sql) {
        var bindings = new LinkedHashMap<Integer, Binding>();
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                    if (method.getName().startsWith("set") && arguments != null && arguments.length >= 2
                            && arguments[0] instanceof Integer index) {
                        bindings.put(index, new Binding(method, arguments.clone()));
                    } else if (method.getName().equals("clearParameters")) {
                        bindings.clear();
                    } else if (method.getName().equals("executeQuery") && QUERIES.get() != null
                            && sql.stripLeading().regionMatches(true, 0, "select", 0, 6)) {
                        QUERIES.get().add(new BoundQuery(sql, List.copyOf(bindings.values())));
                    }
                    return invoke(method, delegate, arguments);
                });
    }

    private static Object invoke(Method method, Object target, Object[] arguments) throws Exception {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }
}
