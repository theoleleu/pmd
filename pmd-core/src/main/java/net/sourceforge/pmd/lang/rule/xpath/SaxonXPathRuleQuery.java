/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.rule.xpath;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import net.sourceforge.pmd.RuleContext;
import net.sourceforge.pmd.lang.ast.Node;
import net.sourceforge.pmd.lang.ast.xpath.saxon.DocumentNode;
import net.sourceforge.pmd.lang.ast.xpath.saxon.ElementNode;
import net.sourceforge.pmd.lang.xpath.Initializer;
import net.sourceforge.pmd.properties.PropertyDescriptor;

import net.sf.saxon.Configuration;
import net.sf.saxon.expr.AxisExpression;
import net.sf.saxon.expr.Expression;
import net.sf.saxon.expr.FilterExpression;
import net.sf.saxon.expr.PathExpression;
import net.sf.saxon.expr.RootExpression;
import net.sf.saxon.expr.Token;
import net.sf.saxon.expr.VennExpression;
import net.sf.saxon.om.Axis;
import net.sf.saxon.om.Item;
import net.sf.saxon.om.NamespaceConstant;
import net.sf.saxon.om.SequenceIterator;
import net.sf.saxon.om.ValueRepresentation;
import net.sf.saxon.sort.DocumentSorter;
import net.sf.saxon.sxpath.AbstractStaticContext;
import net.sf.saxon.sxpath.IndependentContext;
import net.sf.saxon.sxpath.XPathDynamicContext;
import net.sf.saxon.sxpath.XPathEvaluator;
import net.sf.saxon.sxpath.XPathExpression;
import net.sf.saxon.sxpath.XPathStaticContext;
import net.sf.saxon.sxpath.XPathVariable;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.type.Type;
import net.sf.saxon.type.TypeHierarchy;
import net.sf.saxon.value.AtomicValue;
import net.sf.saxon.value.BigIntegerValue;
import net.sf.saxon.value.BooleanValue;
import net.sf.saxon.value.DoubleValue;
import net.sf.saxon.value.EmptySequence;
import net.sf.saxon.value.FloatValue;
import net.sf.saxon.value.Int64Value;
import net.sf.saxon.value.SequenceExtent;
import net.sf.saxon.value.StringValue;
import net.sf.saxon.value.UntypedAtomicValue;
import net.sf.saxon.value.Value;

/**
 * This is a Saxon based XPathRule query.
 */
public class SaxonXPathRuleQuery extends AbstractXPathRuleQuery {
    /**
     * Special nodeName that references the root expression.
     */
    static final String AST_ROOT = "_AST_ROOT_";

    private static final Logger LOG = Logger.getLogger(SaxonXPathRuleQuery.class.getName());

    private static final int MAX_CACHE_SIZE = 20;
    private static final Map<Node, DocumentNode> CACHE = new LinkedHashMap<Node, DocumentNode>(MAX_CACHE_SIZE) {
        private static final long serialVersionUID = -7653916493967142443L;

        @Override
        protected boolean removeEldestEntry(final Map.Entry<Node, DocumentNode> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    /**
     * Contains for each nodeName a sub expression, used for implementing rule chain.
     */
    Map<String, List<Expression>> nodeNameToXPaths = new HashMap<>();

    /**
     * Representation of an XPath query, created at {@link #initializeXPathExpression()} using {@link #xpath}.
     */
    private XPathExpression xpathExpression;

    /**
     * Holds the static context later used to match the variables in the dynamic context in
     * {@link #createDynamicContext(ElementNode)}. Created at {@link #initializeXPathExpression()}
     * using the properties descriptors in {@link #properties}.
     */
    private List<XPathVariable> xpathVariables;

    @Override
    public boolean isSupportedVersion(String version) {
        return XPATH_1_0_COMPATIBILITY.equals(version) || XPATH_2_0.equals(version);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Node> evaluate(final Node node, final RuleContext data) {
        initializeXPathExpression();

        try {
            final DocumentNode documentNode = getDocumentNodeForRootNode(node);

            // Map AST Node -> Saxon Node
            final ElementNode rootElementNode = documentNode.nodeToElementNode.get(node);
            final XPathDynamicContext xpathDynamicContext = createDynamicContext(rootElementNode);

            final List<ElementNode> nodes = new LinkedList<>();
            List<Expression> expressions = getXPathExpressionForNodeOrDefault(node.getXPathNodeName());
            for (Expression expression : expressions) {
                SequenceIterator iterator = expression.iterate(xpathDynamicContext.getXPathContextObject());
                Item current = iterator.next();
                while (current != null) {
                    nodes.add((ElementNode) current);
                    current = iterator.next();
                }
            }

            /*
             Map List of Saxon Nodes -> List of AST Nodes, which were detected to match the XPath expression
             (i.e. violation found)
              */
            final List<Node> results = new ArrayList<>(nodes.size());
            for (final ElementNode elementNode : nodes) {
                results.add((Node) elementNode.getUnderlyingNode());
            }
            return results;
        } catch (final XPathException e) {
            throw new RuntimeException(super.xpath + " had problem: " + e.getMessage(), e);
        }
    }

    private List<Expression> getXPathExpressionForNodeOrDefault(String nodeName) {
        if (nodeNameToXPaths.containsKey(nodeName)) {
            return nodeNameToXPaths.get(nodeName);
        }
        return nodeNameToXPaths.get(AST_ROOT);
    }

    /**
     * Attempt to create a dynamic context on which to evaluate the {@link #xpathExpression}.
     *
     * @param elementNode the node on which to create the context; generally this node is the root node of the Saxon
     *                    Tree
     * @return the dynamic context on which to run the query
     * @throws XPathException if the supplied value does not conform to the required type of the
     * variable, when setting up the dynamic context; or if the supplied value contains a node that does not belong to
     * this Configuration (or another Configuration that shares the same namePool)
     */
    private XPathDynamicContext createDynamicContext(final ElementNode elementNode) throws XPathException {
        final XPathDynamicContext dynamicContext = xpathExpression.createDynamicContext(elementNode);

        // Set variable values on the dynamic context
        for (final XPathVariable xpathVariable : xpathVariables) {
            final String variableName = xpathVariable.getVariableQName().getLocalName();
            for (final Map.Entry<PropertyDescriptor<?>, Object> entry : super.properties.entrySet()) {
                if (variableName.equals(entry.getKey().name())) {
                    final ValueRepresentation valueRepresentation = getRepresentation(entry.getKey(), entry.getValue());
                    dynamicContext.setVariable(xpathVariable, valueRepresentation);
                }
            }
        }
        return dynamicContext;
    }


    private ValueRepresentation getRepresentation(final PropertyDescriptor<?> descriptor, final Object value) {
        if (descriptor.isMultiValue()) {
            return getSequenceRepresentation((List<?>) value);
        } else {
            return getAtomicRepresentation(value);
        }
    }

    /**
     * Gets the DocumentNode representation for the whole AST in which the node is, that is, if the node is not the root
     * of the AST, then the AST is traversed all the way up until the root node is found. If the DocumentNode was
     * cached because this method was previously called, then a new DocumentNode will not be instanced.
     *
     * @param node the node from which the root node will be looked for.
     * @return the DocumentNode representing the whole AST
     */
    private DocumentNode getDocumentNodeForRootNode(final Node node) {
        final Node root = getRootNode(node);

        DocumentNode documentNode;
        synchronized (CACHE) {
            documentNode = CACHE.get(root);
            if (documentNode == null) {
                documentNode = new DocumentNode(root);
                CACHE.put(root, documentNode);
            }
        }
        return documentNode;
    }

    /**
     * Traverse the AST until the root node is found.
     *
     * @param node the node from where to start traversing the tree
     * @return the root node
     */
    private Node getRootNode(final Node node) {
        Node root = node;
        while (root.getParent() != null) {
            root = root.getParent();
        }
        return root;
    }

    private void addExpressionForNode(String nodeName, Expression expression) {
        if (!nodeNameToXPaths.containsKey(nodeName)) {
            nodeNameToXPaths.put(nodeName, new LinkedList<Expression>());
        }
        nodeNameToXPaths.get(nodeName).add(expression);
    }

    /**
     * Initialize the {@link #xpathExpression} and the {@link #xpathVariables}.
     */
    private void initializeXPathExpression() {
        if (xpathExpression != null) {
            return;
        }
        try {
            final XPathEvaluator xpathEvaluator = new XPathEvaluator();
            final XPathStaticContext xpathStaticContext = xpathEvaluator.getStaticContext();

            // Enable XPath 1.0 compatibility
            if (XPATH_1_0_COMPATIBILITY.equals(version)) {
                ((AbstractStaticContext) xpathStaticContext).setBackwardsCompatibilityMode(true);
            }

            ((IndependentContext) xpathEvaluator.getStaticContext()).declareNamespace("fn", NamespaceConstant.FN);

            // Register PMD functions
            Initializer.initialize((IndependentContext) xpathStaticContext);

            /*
            Create XPathVariables for later use. It is a Saxon quirk that XPathVariables must be defined on the
            static context, and reused later to associate an actual value on the dynamic context creation, in
            createDynamicContext(ElementNode).
            */
            xpathVariables = new ArrayList<>();
            for (final PropertyDescriptor<?> propertyDescriptor : super.properties.keySet()) {
                final String name = propertyDescriptor.name();
                if (!"xpath".equals(name)) {
                    final XPathVariable xpathVariable = xpathStaticContext.declareVariable(null, name);
                    xpathVariables.add(xpathVariable);
                }
            }

            xpathExpression = xpathEvaluator.createExpression(super.xpath);
            analyzeXPathForRuleChain(xpathEvaluator);
        } catch (final XPathException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Analyzes the xpath expression if it is simple enough to be split apart and represented by
     * multiple single xpath expressions.
     *
     * <p>Only the first level of element selection is checked, possibly combined with a union expression.
     *
     * <p>Example: The XPath expression <code>//A[condition()]/B | //C</code> results in two rule chain visits for the nodes
     * "A" and "C". When visiting A, the XPath <code>self::node[condition()]/B</code> is executed, when visiting
     * "C" the XPath <code>self::node()</code> is executed. At the end, the result of all executions is combined,
     * since these were part of a union.
     *
     * <p>If the XPath expression is more complex, then no rule chain visit nodes are declared and the normal
     * XPath query is executed.
     */
    private void analyzeXPathForRuleChain(final XPathEvaluator xpathEvaluator) {
        Configuration config = xpathEvaluator.getConfiguration();
        TypeHierarchy th = new TypeHierarchy(config);
        boolean useRuleChain = true;
        final Deque<Expression> pending = new ArrayDeque<>();
        pending.push(xpathExpression.getInternalExpression());
        while (!pending.isEmpty()) {
            final Expression node = pending.pop();

            // Need to prove we can handle this part of the query
            boolean valid = false;

            if (node instanceof VennExpression) {
                VennExpression venn = (VennExpression) node;
                if (venn.getOperator() == Token.UNION) {
                    pending.addAll(Arrays.asList(venn.getOperands()));
                    valid = true;
                }
            } else if (node instanceof DocumentSorter) {
                // document sorter is ignored...
                DocumentSorter sorter = (DocumentSorter) node;
                pending.add(sorter.getBaseExpression());
                valid = true;
            } else if (node instanceof PathExpression) {
                PathExpression path = (PathExpression) node;
                // Path expression e.g. "//A[condition()]/B...". First step would be "//A[condition()]"
                Expression firstStep = path.getFirstStep();
                if (firstStep.getItemType(th).getPrimitiveType() == Type.ELEMENT) {
                    if (firstStep instanceof FilterExpression) {
                        FilterExpression filterExpression = (FilterExpression) firstStep;
                        if (filterExpression.getBaseExpression() instanceof PathExpression) {
                            PathExpression root = (PathExpression) filterExpression.getBaseExpression();
                            if (root.getStartExpression() instanceof RootExpression && root.getStepExpression() instanceof AxisExpression) {
                                AxisExpression axis = (AxisExpression) root.getStepExpression();
                                String nodeName = config.getNamePool().getClarkName(axis.getNodeTest().getFingerprint());
                                AxisExpression a = new AxisExpression(Axis.SELF, null);
                                FilterExpression newfilter = new FilterExpression(a, filterExpression.getFilter());
                                PathExpression p = new PathExpression(newfilter, path.getRemainingSteps());
                                addExpressionForNode(nodeName, p);
                                valid = true;
                            }
                        }
                    }
                } else if (firstStep.getItemType(th).getPrimitiveType() == Type.DOCUMENT) {
                    // Path expression without filter, e.g. "//A"
                    if (firstStep instanceof RootExpression && path.getRemainingSteps() instanceof AxisExpression) {
                        AxisExpression axis = (AxisExpression) path.getStepExpression();
                        String nodeName = config.getNamePool().getClarkName(axis.getNodeTest().getFingerprint());
                        AxisExpression a = new AxisExpression(Axis.SELF, null);
                        addExpressionForNode(nodeName, a);
                        valid = true;
                    }
                }
            } else if (node instanceof FilterExpression) {
                // FilterExpression e.g. "//A[condition()]"
                FilterExpression filterExpression = (FilterExpression) node;
                if (filterExpression.getBaseExpression() instanceof PathExpression) {
                    PathExpression root = (PathExpression) filterExpression.getBaseExpression();
                    if (root.getStartExpression() instanceof RootExpression && root.getStepExpression() instanceof AxisExpression) {
                        AxisExpression axis = (AxisExpression) root.getStepExpression();
                        String nodeName = config.getNamePool().getClarkName(axis.getNodeTest().getFingerprint());
                        AxisExpression a = new AxisExpression(Axis.SELF, null);
                        FilterExpression newfilter = new FilterExpression(a, filterExpression.getFilter());
                        addExpressionForNode(nodeName, newfilter);
                        valid = true;
                    }
                }
            }

            if (!valid) {
                useRuleChain = false;
                break;
            }
        }

        if (useRuleChain) {
            super.ruleChainVisits.addAll(nodeNameToXPaths.keySet());
        } else {
            nodeNameToXPaths.clear();
            if (LOG.isLoggable(Level.FINE)) {
                LOG.log(Level.FINE, "Unable to use RuleChain for XPath: " + xpath);
            }
        }

        // always add fallback expression
        addExpressionForNode(AST_ROOT, xpathExpression.getInternalExpression());
    }

    /**
     * Gets the Saxon representation of the parameter, if its type corresponds
     * to an XPath 2.0 atomic datatype.
     *
     * @param value The value to convert
     *
     * @return The converted AtomicValue
     */
    public static AtomicValue getAtomicRepresentation(final Object value) {

        /*
        FUTURE When supported, we should consider refactor this implementation to use Pattern Matching
        (see http://openjdk.java.net/jeps/305) so that it looks clearer.
        */
        if (value == null) {
            return UntypedAtomicValue.ZERO_LENGTH_UNTYPED;
        } else if (value instanceof Enum) {
            // enums use their toString
            return new StringValue(value.toString());
        } else if (value instanceof String) {
            return new StringValue((String) value);
        } else if (value instanceof Boolean) {
            return BooleanValue.get((Boolean) value);
        } else if (value instanceof Integer) {
            return Int64Value.makeIntegerValue((Integer) value);
        } else if (value instanceof Long) {
            return new BigIntegerValue((Long) value);
        } else if (value instanceof Double) {
            return new DoubleValue((Double) value);
        } else if (value instanceof Character) {
            return new StringValue(value.toString());
        } else if (value instanceof Float) {
            return new FloatValue((Float) value);
        } else if (value instanceof Pattern) {
            return new StringValue(String.valueOf(value));
        } else {
            // We could maybe use UntypedAtomicValue
            throw new RuntimeException("Unable to create ValueRepresentation for value of type: " + value.getClass());
        }
    }

    public static Value getSequenceRepresentation(List<?> list) {
        if (list == null || list.isEmpty()) {
            return EmptySequence.getInstance();
        }
        final Item[] converted = new Item[list.size()];
        for (int i = 0; i < list.size(); i++) {
            converted[i] = getAtomicRepresentation(list.get(i));
        }
        return new SequenceExtent(converted);
    }

    @Override
    public List<String> getRuleChainVisits() {
        initializeXPathExpression();
        return super.getRuleChainVisits();
    }
}
