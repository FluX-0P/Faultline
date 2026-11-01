package dev.faultline.cli.source;

import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import dev.faultline.cli.source.JavaSources.Method;

/**
 * The tables a JPA app writes when it saves through a Spring Data repository: the saved entity's table, the tables of
 * entities saved with it by a cascade, and the join tables of its collections. Names follow Hibernate with Spring Boot's
 * default naming: {@code @Table(name)}, else the entity name in snake_case (ShipmentLine -> shipment_line); a
 * collection without {@code mappedBy} or {@code @JoinColumn} gets a join table named owner_table_field.
 */
public final class JpaTables {
    private static final Set<String> REPOSITORIES = Set.of("Repository", "CrudRepository", "ListCrudRepository", "PagingAndSortingRepository",
        "ListPagingAndSortingRepository", "JpaRepository");
    private static final Set<String> SAVES = Set.of("save", "saveAll", "saveAndFlush", "saveAllAndFlush");
    private static final Set<String> RELATIONS = Set.of("OneToMany", "OneToOne", "ManyToMany", "ManyToOne");

    private JpaTables() {}

    /** The tables the method writes through repository save calls, in lower case. */
    public static Set<String> saved(JavaSources code, Method m) {
        var tables = new TreeSet<String>();
        for (var call : m.declaration().findAll(MethodCallExpr.class, c -> SAVES.contains(c.getNameAsString()))) {
            code.receiverType(m, call).flatMap(t -> code.type(m.module(), t)).flatMap(JpaTables::entityOf)
                .flatMap(e -> code.type(m.module(), e)).ifPresent(entity -> tables(code, m.module(), entity, tables, new HashSet<>()));
        }
        return tables;
    }

    /** The entity a Spring Data repository interface keeps: the first type argument of the repository it extends. */
    static Optional<String> entityOf(TypeDeclaration<?> type) {
        if (!(type instanceof ClassOrInterfaceDeclaration c) || !c.isInterface()) return Optional.empty();
        for (ClassOrInterfaceType parent : c.getExtendedTypes()) {
            if (REPOSITORIES.contains(parent.getNameAsString()) && parent.getTypeArguments().map(a -> !a.isEmpty()).orElse(false)) {
                return Optional.of(simpleName(parent.getTypeArguments().get().get(0).asString()));
            }
        }
        return Optional.empty();
    }

    private static void tables(JavaSources code, String module, TypeDeclaration<?> entity, Set<String> into, Set<String> seen) {
        if (entity.getAnnotationByName("Entity").isEmpty() || !seen.add(entity.getNameAsString())) return;
        String table = table(entity);
        into.add(table);
        for (FieldDeclaration field : entity.getFields()) {
            var relation = field.getAnnotations().stream().filter(a -> RELATIONS.contains(a.getNameAsString())).findFirst();
            boolean elements = field.getAnnotationByName("ElementCollection").isPresent();
            if (relation.isEmpty() && !elements) continue;
            String fieldName = field.getVariable(0).getNameAsString();
            String target = elementType(field.getElementType().asString());
            boolean collection = !target.equals(simpleName(field.getElementType().asString()));
            boolean owned = relation.map(a -> attribute(a, "mappedBy") == null).orElse(true) && field.getAnnotationByName("JoinColumn").isEmpty();
            if (elements || collection && owned && !relation.get().getNameAsString().equals("ManyToOne")) into.add(table + "_" + snake(fieldName));
            if (relation.isPresent() && cascades(relation.get())) code.type(module, target).ifPresent(t -> tables(code, module, t, into, seen));
        }
    }

    /** {@code @Table(name)}, else the entity's name ({@code @Entity(name)} or the class's), in snake_case and lower case. */
    static String table(TypeDeclaration<?> entity) {
        String name = entity.getAnnotationByName("Table").map(a -> attribute(a, "name")).orElse(null);
        if (name == null) name = entity.getAnnotationByName("Entity").map(a -> attribute(a, "name")).orElse(null);
        if (name == null) return snake(entity.getNameAsString());
        return name.replace("\"", "").replace("`", "").toLowerCase(Locale.ROOT);
    }

    private static boolean cascades(AnnotationExpr relation) {
        String cascade = attribute(relation, "cascade");
        return cascade != null && (cascade.contains("ALL") || cascade.contains("PERSIST") || cascade.contains("MERGE"));
    }

    /** An attribute's value as written, without the quotes of a string; null if the annotation does not set it. */
    private static String attribute(AnnotationExpr a, String name) {
        if (a instanceof SingleMemberAnnotationExpr s && name.equals("value")) return unquote(s.getMemberValue().toString());
        if (a instanceof NormalAnnotationExpr n) {
            return n.getPairs().stream().filter(p -> p.getNameAsString().equals(name)).map(p -> unquote(p.getValue().toString())).findFirst().orElse(null);
        }
        return null;
    }

    private static String unquote(String s) {
        return s.length() > 1 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    /** List<OrderLine> -> OrderLine; a type that is not a collection stays as it is. */
    private static String elementType(String type) {
        if (!type.contains("<")) return simpleName(type);
        String args = type.substring(type.indexOf('<') + 1, type.lastIndexOf('>'));
        return simpleName(args.contains(",") ? args.substring(args.lastIndexOf(',') + 1).strip() : args);
    }

    private static String simpleName(String type) {
        String raw = type.contains("<") ? type.substring(0, type.indexOf('<')) : type;
        return raw.substring(raw.lastIndexOf('.') + 1).strip();
    }

    /** ShipmentLine -> shipment_line, as Spring Boot's CamelCaseToUnderscoresNamingStrategy names it. */
    static String snake(String name) {
        var sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && Character.isLowerCase(name.charAt(i - 1))) sb.append('_');
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
