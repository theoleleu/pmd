/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.java.rule.codestyle;

import java.util.regex.Pattern;

import net.sourceforge.pmd.lang.java.ast.ASTAnnotationTypeDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTAnyTypeDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTClassOrInterfaceDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTEnumDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTRecordDeclaration;
import net.sourceforge.pmd.lang.java.ast.internal.PrettyPrintingUtil;
import net.sourceforge.pmd.lang.java.rule.internal.JavaRuleUtil;
import net.sourceforge.pmd.properties.PropertyDescriptor;


/**
 * Configurable naming conventions for type declarations.
 */
public class ClassNamingConventionsRule extends AbstractNamingConventionRule<ASTAnyTypeDeclaration> {

    private final PropertyDescriptor<Pattern> classRegex = defaultProp("class", "concrete class").build();
    private final PropertyDescriptor<Pattern> abstractClassRegex = defaultProp("abstract class").build();
    private final PropertyDescriptor<Pattern> interfaceRegex = defaultProp("interface").build();
    private final PropertyDescriptor<Pattern> enumerationRegex = defaultProp("enum").build();
    private final PropertyDescriptor<Pattern> annotationRegex = defaultProp("annotation").build();
    private final PropertyDescriptor<Pattern> utilityClassRegex = defaultProp("utility class").defaultValue("[A-Z][a-zA-Z0-9]+(Utils?|Helper|Constants)").build();


    public ClassNamingConventionsRule() {
        super(ASTAnyTypeDeclaration.class,
              ASTEnumDeclaration.class,
              ASTAnnotationTypeDeclaration.class,
              ASTRecordDeclaration.class);
        definePropertyDescriptor(classRegex);
        definePropertyDescriptor(abstractClassRegex);
        definePropertyDescriptor(interfaceRegex);
        definePropertyDescriptor(enumerationRegex);
        definePropertyDescriptor(annotationRegex);
        definePropertyDescriptor(utilityClassRegex);
    }

    @Override
    public Object visit(ASTClassOrInterfaceDeclaration node, Object data) {

        if (node.isAbstract()) {
            checkMatches(node, abstractClassRegex, data);
        } else if (JavaRuleUtil.isUtilityClass(node)) {
            checkMatches(node, utilityClassRegex, data);
        } else if (node.isInterface()) {
            checkMatches(node, interfaceRegex, data);
        } else {
            checkMatches(node, classRegex, data);
        }

        return data;
    }


    @Override
    public Object visit(ASTEnumDeclaration node, Object data) {
        checkMatches(node, enumerationRegex, data);
        return data;
    }

    @Override
    public Object visit(ASTRecordDeclaration node, Object data) {
        checkMatches(node, classRegex, data); // property?
        return data;
    }

    @Override
    public Object visit(ASTAnnotationTypeDeclaration node, Object data) {
        checkMatches(node, annotationRegex, data);
        return data;
    }


    @Override
    String defaultConvention() {
        return PASCAL_CASE;
    }

    @Override
    String nameExtractor(ASTAnyTypeDeclaration node) {
        return node.getSimpleName();
    }


    @Override
    String kindDisplayName(ASTAnyTypeDeclaration node, PropertyDescriptor<Pattern> descriptor) {
        return JavaRuleUtil.isUtilityClass(node) ? "utility class" : PrettyPrintingUtil.kindName(node);
    }
}
