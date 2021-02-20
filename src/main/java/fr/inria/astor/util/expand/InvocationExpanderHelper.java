package fr.inria.astor.util.expand;

import fr.inria.astor.core.manipulation.MutationSupporter;
import fr.inria.astor.core.manipulation.sourcecode.VariableResolver;
import one.util.streamex.StreamEx;
import org.paukov.combinatorics3.Generator;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.code.UnaryOperatorKind;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.factory.CodeFactory;
import spoon.reflect.factory.TypeFactory;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtLocalVariableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.support.reflect.code.CtInvocationImpl;
import spoon.support.reflect.code.CtUnaryOperatorImpl;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Helper class for {@link InvocationExpander}
 */
public class InvocationExpanderHelper {

    TypeFactory typeFactory = MutationSupporter.factory.Type();
    CodeFactory codeFactory = MutationSupporter.factory.Code();


    /**
     * Given an invocation, create all possible permutations (of the arguments) from that invocations
     * Example: Given the CtInvocation classA.getMethod(a, b ,c) return:
     * classA.getMethod(a, b ,c)
     * classA.getMethod(a, c ,b)
     * classA.getMethod(b, a ,c)
     * classA.getMethod(b, c ,a)
     * classA.getMethod(c, b ,a)
     * classA.getMethod(c, a ,b)
     *
     * @param ctInvocation .
     * @return .
     */
    public Set<CtInvocationImpl> createAllPermutationsFromInvocation(CtInvocationImpl ctInvocation) {
        List<CtExpression<?>> arguments = ctInvocation.getArguments();
        Set<List<CtExpression<?>>> argumentsPermutations = getAllPermutations(arguments);
        return argumentsPermutations.stream().map(permutation -> createInvocationWithArguments(ctInvocation, permutation)).collect(Collectors.toSet());
    }

    /**
     * Given a list of arguments in the form (a , b ,c) then return all possible permutations:
     * (a, c ,b)
     * (b, a ,c)
     * (b, c ,a)
     * (c, b ,a)
     * (c, a ,b)
     *
     * @param arguments .
     * @return .
     */
    private Set<List<CtExpression<?>>> getAllPermutations(List<CtExpression<?>> arguments) {
        return Generator.permutation(arguments)
                .simple()
                .stream().collect(Collectors.toSet());
    }

    private CtInvocationImpl createInvocationWithArguments(CtInvocationImpl ctInvocation, List<CtExpression<?>> arguments) {
        CtInvocationImpl clonedInvocation = (CtInvocationImpl) MutationSupporter.clone(ctInvocation);
        clonedInvocation.setArguments(arguments);
        return clonedInvocation;
    }

    /**
     * Given an invocation, return a negated version of this invocation.
     * It returns  negated invocation if this invocation if of boolean type.
     *
     * @param invocation .
     * @return .
     */
    public CtExpression createNegatedInvocation(CtInvocationImpl invocation) {
        CtUnaryOperatorImpl ctUnaryOperator = new CtUnaryOperatorImpl();
        CtInvocationImpl clonedInvocation = (CtInvocationImpl) MutationSupporter.clone(invocation);
        clonedInvocation.setParent(invocation.getParent());
        ctUnaryOperator.setOperand(clonedInvocation);
        ctUnaryOperator.setParent(invocation.getParent());
        ctUnaryOperator.setType(typeFactory.booleanPrimitiveType());
        ctUnaryOperator.setKind(UnaryOperatorKind.NOT);
        // Weird case of spoon. Spoon adds parentheses when ctUnaryOperator's parent is same
        String possibleUnaryWithoutParenthesis = ctUnaryOperator.toString().substring(1, ctUnaryOperator.toString().length() - 1);
        if (possibleUnaryWithoutParenthesis.equals(ctUnaryOperator.getParent().toString()))
            ctUnaryOperator = (CtUnaryOperatorImpl) ctUnaryOperator.getParent();
        return ctUnaryOperator;
    }

    public Set<CtInvocationImpl> createInvocationsWithArgExecutables(Set<CtInvocationImpl> uniqueInvocations) {
        Set<CtInvocationImpl> expandedInvocationsWithArgs =
                uniqueInvocations.stream().map(this::createInvocationsWithInheritedExecutables)
                        .flatMap(Set::stream)
                        .collect(Collectors.toSet());
        //remove invocations that are duplicate (by String)
        return StreamEx.of(expandedInvocationsWithArgs).distinct(CtInvocationImpl::toString).collect(Collectors.toSet());
    }

    public Set<CtInvocationImpl> createInvocationsWithNoArgExecutables(Set<CtInvocationImpl> invocationsWithUniqueTarget) {
        Set<CtInvocationImpl> expandedInvocationsWithNoParams = new HashSet<>();
        invocationsWithUniqueTarget.forEach(invocation -> {
            Collection<CtExecutableReference<?>> executables = getExecutablesWithNoArgs(invocation);
            executables.forEach(executable -> {
                CtInvocationImpl clonedInvocation = (CtInvocationImpl) MutationSupporter.clone(invocation);
                clonedInvocation.setParent(invocation.getParent());
                clonedInvocation.setExecutable(executable);
                if (executable.getParameters().isEmpty())
                    clonedInvocation.setArguments(executable.getActualTypeArguments());
                expandedInvocationsWithNoParams.add(clonedInvocation);
            });
        });

        return expandedInvocationsWithNoParams;
    }

    private Set<CtExecutableReference<?>> getExecutablesWithNoArgs(CtInvocationImpl ctInvocation) {
        return ctInvocation.getExecutable().getDeclaringType().getAllExecutables()
                .stream().filter(executable -> executable.getParameters().isEmpty()).collect(Collectors.toSet());
    }

    private Set<CtExecutableReference<?>> getExecutablesWithArgs(CtInvocationImpl ctInvocation) {
        return ctInvocation.getExecutable().getDeclaringType().getAllExecutables()
                .stream().filter(executable -> !executable.getParameters().isEmpty()).collect(Collectors.toSet());
    }

    /**
     * TODO FIX THIS
     * Given a invocation myClass.method(a, b) return a List of expressions in the form ["var_0" , "var_1"]
     * Where each expression has the same type of the original invocation's corresponding argument
     * If in this case both arguments of the invocation are of type double then return a list of expressions of
     * type double
     *
     * @param invocation the invocation from where we create a template
     * @return the templated invocation
     */
    private List<CtExpression<?>> getTemplatedArgumentsFromInvocation(CtInvocationImpl invocation) {
        List<CtTypeReference<?>> parameters = invocation.getExecutable().getParameters();
        List<CtExpression<?>> templateArguments = new LinkedList<>();
        AtomicInteger nrVars = new AtomicInteger(0); // we want a different var name for each argument
        parameters.forEach(param -> {
            String varNumber = String.valueOf(nrVars.getAndIncrement());
            CtVariableAccess newTemplate = createVarFromType(param, "var_" + varNumber);
            newTemplate.setParent(invocation.getParent());
            templateArguments.add(newTemplate);
        });

        return templateArguments;
    }


    private Set<CtInvocationImpl> createInvocationsWithInheritedExecutables(CtInvocationImpl invocation) {
        Collection<CtExecutableReference<?>> executables = getExecutablesWithArgs((CtInvocationImpl) MutationSupporter.clone(invocation));
        return executables.stream().map(executable ->
                createInvocationWithExecutable((CtInvocationImpl) MutationSupporter.clone(invocation), executable))
                .collect(Collectors.toSet());
    }

    private CtInvocationImpl createInvocationWithExecutable(CtInvocationImpl invocation, CtExecutableReference<?> executable) {
        CtInvocationImpl clonedInvocation = (CtInvocationImpl) MutationSupporter.clone(invocation);
        clonedInvocation.setExecutable(executable);
        List<CtExpression<?>> templatedArgumentsFromInvocation = getTemplatedArgumentsFromInvocation(clonedInvocation);
        clonedInvocation.setArguments(templatedArgumentsFromInvocation);
        formatIngredient(clonedInvocation);
        return clonedInvocation;
    }

    // taken from already existing code in Astor. author probably Matias or Martin
    public void formatIngredient(CtElement ingredientCtElement) {

        List<CtVariableAccess> varAccessCollected = VariableResolver.collectVariableAccess(ingredientCtElement, true);
        Map<String, String> varMappings = new HashMap<>();
        int nrvar = 0;
        for (int i = 0; i < varAccessCollected.size(); i++) {
            CtVariableAccess var = varAccessCollected.get(i);

            if (VariableResolver.isStatic(var.getVariable())) {
                continue;
            }

            String abstractName = "";
            if (!varMappings.containsKey(var.getVariable().getSimpleName())) {
                String currentTypeName = var.getVariable().getType().getSimpleName();
                if (currentTypeName.contains("?")) {
                    // Any change in case of ?
                    abstractName = var.getVariable().getSimpleName();
                } else {
                    abstractName = "_" + currentTypeName + "_" + nrvar;
                }
                varMappings.put(var.getVariable().getSimpleName(), abstractName);
                nrvar++;
            } else {
                abstractName = varMappings.get(var.getVariable().getSimpleName());
            }

            var.getVariable().setSimpleName(abstractName);
            // workaround: Problems with var Shadowing
            var.getFactory().getEnvironment().setNoClasspath(true);
            if (var instanceof CtFieldAccess) {
                CtFieldAccess fieldAccess = (CtFieldAccess) var;
                fieldAccess.getVariable().setDeclaringType(null);
            }

        }

    }

    public CtVariableAccess createVarFromType(CtTypeReference ctTypeReference, String name) {
        CtLocalVariableReference varReference = codeFactory.createLocalVariableReference(ctTypeReference, name);
        return codeFactory.createVariableRead(varReference, false);
    }


}
