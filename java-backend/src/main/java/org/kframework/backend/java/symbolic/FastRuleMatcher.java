// Copyright (c) 2015-2016 K Team. All Rights Reserved.

package org.kframework.backend.java.symbolic;

import org.kframework.attributes.Att;
import org.kframework.backend.java.builtins.BoolToken;
import org.kframework.backend.java.compile.KOREtoBackendKIL;
import org.kframework.backend.java.kil.BuiltinList;
import org.kframework.backend.java.kil.BuiltinMap;
import org.kframework.backend.java.kil.ConstrainedTerm;
import org.kframework.backend.java.kil.GlobalContext;
import org.kframework.backend.java.kil.InnerRHSRewrite;
import org.kframework.backend.java.kil.KItem;
import org.kframework.backend.java.kil.KLabelConstant;
import org.kframework.backend.java.kil.KList;
import org.kframework.backend.java.kil.LocalRewriteTerm;
import org.kframework.backend.java.kil.Rule;
import org.kframework.backend.java.kil.RuleAutomatonDisjunction;
import org.kframework.backend.java.kil.Term;
import org.kframework.backend.java.kil.TermContext;
import org.kframework.backend.java.kil.Token;
import org.kframework.backend.java.kil.Variable;
import org.kframework.builtin.KLabels;
import org.kframework.kore.KApply;
import org.kframework.utils.BitSet;

import static org.kframework.Collections.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multisets;
import com.google.common.collect.Sets;

/**
 * A very fast interpreted matching implementation based on merging the rules into a decision-tree like structure.
 * All possible matches are computed in one matching step. The merged term is obtained by putting a Matching Logic
 * disjunction between the bodies of all rules, and then pushing the disjunction down into the term by identifying
 * common structure. Information about the originating rule is retained via a predicate attached to each element of the
 * disjunction.
 */
public class FastRuleMatcher {

    private ConjunctiveFormula[] constraints;
    private final int ruleCount;

    private BitSet empty;

    private final GlobalContext global;

    private boolean patternFold = false;
    private boolean partialSimplification = false;
    private boolean continuousSimplification = true;
    private TermContext context;

    public static ConjunctiveFormula unify(Term term, Term otherTerm, TermContext context) {
        return new FastRuleMatcher(context.global(), 1).unifyEquality(term, otherTerm, false, false, true, context);
    }

    public static List<Substitution<Variable, Term>> match(Term subject, Term pattern, TermContext context) {
        return new FastRuleMatcher(context.global(), 1).matchSinglePattern(subject, pattern, context);
    }

    public FastRuleMatcher(GlobalContext global, int ruleCount) {
        this.global = global;
        this.ruleCount = ruleCount;
        constraints = new ConjunctiveFormula[this.ruleCount];
    }

    /**
     * Unifies the subject against the possibly-merged pattern.
     *
     * @return a list of constraints tagged with the Integer identifier of the rule they belong to and
     * with a Boolean which is true if the rule matched.
     */
    public List<RuleMatchResult> matchRulePattern(
            ConstrainedTerm subject,
            Term pattern,
            BitSet ruleMask,
            boolean narrowing,
            boolean computeOne,
            List<String> transitions,
            TermContext context) {

        ruleMask.stream().forEach(i -> constraints[i] = ConjunctiveFormula.of(context.global()));
        empty = BitSet.apply(ruleCount);

        BitSet theMatchingRules = match(subject.term(), pattern, ruleMask, List());

        List<RuleMatchResult> structuralResults = new ArrayList<>();
        List<RuleMatchResult> transitionResults = new ArrayList<>();
        for (int i = theMatchingRules.nextSetBit(0); i >= 0; i = theMatchingRules.nextSetBit(i + 1)) {
            Rule rule = global.getDefinition().ruleTable.get(i);
            // TODO(YilongL): remove TermContext from the signature once
            // ConstrainedTerm doesn't hold a TermContext anymore
            /* TODO(AndreiS): remove this hack for super strictness after strategies work */
            ConjunctiveFormula patternConstraint = ConjunctiveFormula.of(rule.lookups());
            if (!computeOne && rule.containsAttribute(Att.cool()) && transitions.stream().anyMatch(rule::containsAttribute)) {
                patternConstraint = patternConstraint.addAll(rule.requires().stream().filter(t -> !t.containsAttribute(Att.transition())).collect(Collectors.toList()));
            } else {
                patternConstraint = patternConstraint.addAll(rule.requires());
            }
            List<Triple<ConjunctiveFormula, Boolean, Map<scala.collection.immutable.List<Pair<Integer, Integer>>, Term>>> ruleResults = ConstrainedTerm.evaluateConstraints(
                    constraints[i],
                    subject.constraint(),
                    patternConstraint,
                    Sets.union(getLeftHandSide(pattern, i).variableSet(), patternConstraint.variableSet()).stream()
                            .filter(v -> !v.name().equals(KOREtoBackendKIL.THE_VARIABLE))
                            .collect(Collectors.toSet()),
                    context);
            for (Triple<ConjunctiveFormula, Boolean, Map<scala.collection.immutable.List<Pair<Integer, Integer>>, Term>> triple : ruleResults) {
                RuleMatchResult result = new RuleMatchResult(triple.getLeft(), triple.getMiddle(), triple.getRight(), i);
                if (transitions.stream().anyMatch(rule::containsAttribute)) {
                    transitionResults.add(result);
                } else {
                    structuralResults.add(result);
                }
            }
        }

        if (!structuralResults.isEmpty()) {
            return narrowing ? structuralResults : structuralResults.subList(0, 1);
        } else if (computeOne && !transitionResults.isEmpty()) {
            return transitionResults.subList(0, 1);
        } else {
            return transitionResults;
        }
    }

    public static class RuleMatchResult {
        public final ConjunctiveFormula constraint;
        public final boolean isMatching;
        public final Map<scala.collection.immutable.List<Pair<Integer, Integer>>, Term> rewrites;
        public final int ruleIndex;

        private RuleMatchResult(
                ConjunctiveFormula constraint,
                boolean isMatching,
                Map<scala.collection.immutable.List<Pair<Integer, Integer>>, Term> rewrites,
                int ruleIndex) {
            this.constraint = constraint;
            this.isMatching = isMatching;
            this.rewrites = rewrites;
            this.ruleIndex = ruleIndex;
        }
    }

    /**
     * Matches the subject against the pattern. The pattern does not contain any disjunctions.
     */
    public ConjunctiveFormula unifyEquality(Term subject, Term pattern, boolean patternFold, boolean partialSimplification, boolean continuousSimplification, TermContext context) {
        this.patternFold = patternFold;
        this.partialSimplification = partialSimplification;
        this.continuousSimplification = continuousSimplification;
        this.context = context;
        constraints[0] = ConjunctiveFormula.of(global);
        empty = BitSet.apply(ruleCount);
        BitSet one = BitSet.apply(1);
        one.makeOnes(1);
        BitSet theMatchingRules = match(subject, pattern, one, List());
        if (theMatchingRules.get(0)) {
            return constraints[0];
        } else {
            return ConjunctiveFormula.of(global).add(unificationFailureLeftHandSide, unificationFailureRightHandSide).simplify();
        }
    }

    /**
     * Matches the subject against the pattern. The pattern does not contain any disjunctions.
     */
    public List<Substitution<Variable, Term>> matchSinglePattern(Term subject, Term pattern, TermContext context) {
        constraints[0] = ConjunctiveFormula.of(global);
        empty = BitSet.apply(ruleCount);
        BitSet one = BitSet.apply(1);
        one.makeOnes(1);
        BitSet theMatchingRules = match(subject, pattern, one, List());
        if (theMatchingRules.get(0)) {
            return constraints[0].getDisjunctiveNormalForm().conjunctions().stream()
                    .map(c -> c.simplify(context))
                    .filter(c -> !c.isFalse())
                    .map(c -> c.orientSubstitution(pattern.variableSet()))
                    .filter(c -> c.isMatching(pattern.variableSet()))
                    .map(c -> c.substitution())
                    .collect(Collectors.toList());
        } else {
            return Collections.emptyList();
        }
    }

    private BitSet match(Term subject, Term pattern, BitSet ruleMask, scala.collection.immutable.List<Pair<Integer, Integer>> path) {
        assert !ruleMask.isEmpty();

        // if the pattern is a variable, try to add its binding to the current solution
        if (pattern instanceof Variable) {
            return addSubstitution((Variable) pattern, subject, ruleMask);
        }

        if (subject.isSymbolic()) {
            return addUnification(subject, pattern, ruleMask, path);
        }

        if (pattern instanceof RuleAutomatonDisjunction) {
            RuleAutomatonDisjunction automatonDisjunction = (RuleAutomatonDisjunction) pattern;
            BitSet returnSet = BitSet.apply(ruleCount);

            // handle variables in the disjunction
            List<Pair<Variable, BitSet>> pairs = automatonDisjunction.getVariablesForSort(subject.sort());
            for (Pair<Variable, BitSet> p : pairs) {
                if (ruleMask.intersects(p.getRight())) {
                    BitSet localRuleMask = ruleMask.clone();
                    localRuleMask.and(p.getRight());
                    returnSet.or(addSubstitution(p.getLeft(), subject, localRuleMask));
                }
            }

            // handle associative constructs with identity
            for (Pair<BuiltinList, BitSet> p : automatonDisjunction.assocDisjunctionArray[subject.sort().ordinal()]) {
                matchInside(subject, ruleMask, path, returnSet, p);
            }

            if (subject instanceof KItem) {
                // main match of KItem
                matchInside(subject, ruleMask, path, returnSet, automatonDisjunction.getKItemPatternForKLabel((KLabelConstant) ((KItem) subject).kLabel()));
                checkVarLabelPatterns(subject, ruleMask, path, automatonDisjunction, returnSet);
            } else if (subject instanceof Token) {
                // and matching Tokens
                BitSet rules = automatonDisjunction.tokenDisjunctions.get(subject);
                if (rules != null) {
                    BitSet localRuleMask = ruleMask.clone();
                    localRuleMask.and(rules);
                    returnSet.or(localRuleMask);
                }
            }

            return returnSet;
        }

        // register the RHS of the rewrite we have just encountered, and continue matching on its LHS
        if (pattern instanceof KItem && ((KItem) pattern).kLabel().toString().equals(KLabels.KREWRITE)) {
            KApply rw = (KApply) pattern;
            InnerRHSRewrite innerRHSRewrite = (InnerRHSRewrite) rw.klist().items().get(1);
            BitSet theNewMask = match(subject, (Term) rw.klist().items().get(0), ruleMask, path);

            for (int i = theNewMask.nextSetBit(0); i >= 0; i = theNewMask.nextSetBit(i + 1)) {
                if (innerRHSRewrite.theRHS[i] != null) {
                    constraints[i] = constraints[i].add(new LocalRewriteTerm(path.reverse(), innerRHSRewrite.theRHS[i]), BoolToken.TRUE);
                }
            }
            return theNewMask;
        }

        if (pattern.isSymbolic()) {
            return addUnification(subject, pattern, ruleMask, path);
        }

        // normalize associative representations
        if (subject instanceof BuiltinList && !(pattern instanceof BuiltinList)) {
            pattern = ((BuiltinList) subject).upElementToList(pattern);
        } else if (pattern instanceof BuiltinList && !(subject instanceof BuiltinList)) {
            subject = ((BuiltinList) pattern).upElementToList(subject);
        }

        if (subject instanceof KItem && pattern instanceof KItem) {
            KLabelConstant subjectKLabel = (KLabelConstant) ((KItem) subject).kLabel();
            KLabelConstant patternKLabel = (KLabelConstant) ((KItem) pattern).klabel();
            if (subjectKLabel != patternKLabel) {
                return empty;
            }

            KList subjectKList = (KList) ((KItem) subject).kList();
            KList patternKList = (KList) ((KItem) pattern).kList();
            int size = subjectKList.size();

            if (size != patternKList.size()) {
                return empty;
            }

            // main loop matching the klist
            for (int i = 0; i < size; ++i) {
                BitSet childrenDontCareRuleMaskForPosition = ((KItem) pattern).getChildrenDontCareRuleMaskForPosition(i);
                // continue if the pattern under this position only contains "don't care" variables
                if (childrenDontCareRuleMaskForPosition != null && ruleMask.subset(childrenDontCareRuleMaskForPosition)) {
                    continue;
                }

                ruleMask = match(subjectKList.get(i), patternKList.get(i), ruleMask, path.$colon$colon(Pair.of(i, i + 1)));
                if (ruleMask.isEmpty()) {
                    return ruleMask;
                }
            }

            return ruleMask;
        } else if (subject instanceof BuiltinList && pattern instanceof BuiltinList) {
            return matchAssoc((BuiltinList) subject, 0, (BuiltinList) pattern, 0, ruleMask, path);
        } else if (subject instanceof BuiltinMap && pattern instanceof BuiltinMap) {
            return unifyMapModuloPatternFolding((BuiltinMap) subject, (BuiltinMap) pattern, ruleMask, path);
        } else if (subject instanceof Token && pattern instanceof Token) {
            // TODO: make tokens unique?
            return subject.equals(pattern) ? ruleMask : empty;
        } else {
            assert subject instanceof KItem || subject instanceof BuiltinList || subject instanceof Token || subject instanceof BuiltinMap : "unexpected class at matching: " + subject.getClass();
            assert pattern instanceof KItem || pattern instanceof BuiltinList || pattern instanceof Token : "unexpected class at matching: " + pattern.getClass();
            return empty;
        }
    }

    private void checkVarLabelPatterns(Term subject, BitSet ruleMask, scala.collection.immutable.List<Pair<Integer, Integer>> path, RuleAutomatonDisjunction automatonDisjunction, BitSet returnSet) {
        List<Pair<KItem, BitSet>> varLabelPatterns = automatonDisjunction.getKItemPatternByArity(((KItem) subject).klist().size());
        if (!(varLabelPatterns == null)) {
            for (Pair<KItem, BitSet> p : varLabelPatterns) {
                matchInside(subject, ruleMask, path, returnSet, p);
            }
        }
    }

    private void matchInside(Term subject, BitSet ruleMask, scala.collection.immutable.List<Pair<Integer, Integer>> path, BitSet returnSet, Pair<? extends Term, BitSet> pSeq) {
        if (pSeq != null) {
            if (ruleMask.intersects(pSeq.getRight())) {
                BitSet localRuleMaskSeq = ((BitSet) ruleMask.clone());
                localRuleMaskSeq.and(pSeq.getRight());
                localRuleMaskSeq = match(subject, pSeq.getLeft(), localRuleMaskSeq, path);
                returnSet.or(localRuleMaskSeq);
            }
        }
    }

    /**
     * Implements associative matching. The current implementation has the following limitations:
     * * assoc operation signature only of the form "s * s -> s"
     * * only one assoc operation per sort
     * * klabel variables only stand for non-assoc klabels
     * * no klist variables
     */
    private BitSet matchAssoc(BuiltinList subject, int subjectIndex, BuiltinList pattern, int patternIndex, BitSet ruleMask, scala.collection.immutable.List<Pair<Integer, Integer>> path) {
        assert subject.sort.equals(pattern.sort);
        //assert subject.isConcreteCollection();

        /* match prefix of elements in subject and pattern */
        if (subjectIndex == subject.size() && patternIndex == pattern.size()) {
            /* end of matching */
            return ruleMask;
        }

        if (patternIndex == pattern.size()) {
            // fail
            return empty;
        }


        BuiltinList.ElementTailSplit patternElementTailSplit = pattern.splitElementTail(patternIndex, ruleCount);
        if (ruleMask.subset(patternElementTailSplit.combinedMask)) {
            BitSet elementMask;
            if (subjectIndex == subject.size()) {
                // fail
                elementMask = empty;
            } else {
                assert subjectIndex < subject.size();
                elementMask = patternElementTailSplit.elementMask.clone();
                elementMask.and(ruleMask);
                if (!elementMask.isEmpty()) {
                    elementMask = match(subject.get(subjectIndex), patternElementTailSplit.element, elementMask, subject instanceof BuiltinList.SingletonBuiltinList ? path : path.$colon$colon(Pair.of(subjectIndex, subjectIndex + 1)));
                    if (!elementMask.isEmpty()) {
                        elementMask = matchAssoc(subject, subjectIndex + 1, pattern, patternIndex + 1, elementMask, path);
                    }
                }
            }

            BitSet tailMask = patternElementTailSplit.tailMask.clone();
            tailMask.and(ruleMask);
            if (!tailMask.isEmpty()) {
                tailMask = match(subject.range(subjectIndex, subject.size()), patternElementTailSplit.tail, tailMask, path.$colon$colon(Pair.of(subjectIndex, subject.size())));
            }

            BitSet resultSet = elementMask.clone();
            resultSet.or(tailMask);
            return resultSet;
        }

        ListMultimap<Integer, ConjunctiveFormula> nestedConstraints = ArrayListMultimap.create();
        for (int i = subjectIndex; i <= subject.size(); i++) {
            ConjunctiveFormula[] oldConstraints = constraints;
            constraints = new ConjunctiveFormula[constraints.length];
            ruleMask.stream().forEach(j -> constraints[j] = ConjunctiveFormula.of(global));
            BitSet oldRuleMask = ruleMask;
            ruleMask = oldRuleMask.clone();

            /* the path indices for the subject.range list may become inaccurate later on;
            this can only happen when the pattern contains a rewrite with a list pattern in the LHS,
            which means there are no deep-nested rewrites,
            which in turn means the inaccurate paths will never be used */
            ruleMask = match(subject.range(subjectIndex, i), pattern.get(patternIndex), ruleMask, subject instanceof BuiltinList.SingletonBuiltinList ? path : path.$colon$colon(Pair.of(subjectIndex, i)));

            if (!ruleMask.isEmpty()) {
                ruleMask = matchAssoc(subject, i, pattern, patternIndex + 1, ruleMask, path);

                ruleMask.stream().forEach(j -> {
                    if (!constraints[j].isFalse()) {
                        nestedConstraints.put(j, constraints[j]);
                    }
                });
            }

            constraints = oldConstraints;
            ruleMask = oldRuleMask;
        }

        ruleMask = BitSet.apply(ruleCount);
        for (Map.Entry<Integer, Collection<ConjunctiveFormula>> entry : nestedConstraints.asMap().entrySet()) {
            int i = entry.getKey();
            Collection<ConjunctiveFormula> conjunctions = entry.getValue();
            if (conjunctions.size() != 1) {
                constraints[i] = constraints[i].add(new DisjunctiveFormula(conjunctions, global));
            } else {
                constraints[i] = constraints[i].add(conjunctions.iterator().next());
                if (continuousSimplification) {
                    constraints[i] = constraints[i].simplify();
                }
            }
            if (!constraints[i].isFalse()) {
                ruleMask.set(i);
            }
        }

        return ruleMask;
    }


    private BitSet addSubstitution(Variable variable, Term term, BitSet ruleMask) {
        if (variable.name().equals(KOREtoBackendKIL.THE_VARIABLE)) {
            return ruleMask;
        }

        if (variable.equals(term)) {
            return ruleMask;
        }

        if (!global.getDefinition().subsorts().isSubsortedEq(variable.sort(), term.sort())) {
            return empty;
        }

        for (int i = ruleMask.nextSetBit(0); i >= 0; i = ruleMask.nextSetBit(i + 1)) {
            constraints[i] = constraints[i].add(variable, term);
            if (continuousSimplification) {
                constraints[i] = constraints[i].simplify();
            }
            if (constraints[i].isFalse()) {
                ruleMask.clear(i);
            }
        }

        return ruleMask;
    }

    private BitSet addUnification(Term subject, Term pattern, BitSet ruleMask, scala.collection.immutable.List<Pair<Integer, Integer>> path) {
        for (int i = ruleMask.nextSetBit(0); i >= 0; i = ruleMask.nextSetBit(i + 1)) {
            Term leftHandSide = getLeftHandSide(pattern, i);
            Term rightHandSide = getRightHandSide(pattern, i);

            constraints[i] = constraints[i].add(subject, leftHandSide);
            if (continuousSimplification) {
                constraints[i] = constraints[i].simplify();
            }
            if (constraints[i].isFalse()) {
                ruleMask.clear(i);
                continue;
            }

            if (rightHandSide != null) {
                constraints[i] = constraints[i].add(new LocalRewriteTerm(path.reverse(), rightHandSide), BoolToken.TRUE);
            }
        }

        return ruleMask;
    }

    private Term getLeftHandSide(Term pattern, int i) {
        return (Term) pattern.accept(new CopyOnWriteTransformer(null) {
            @Override
            public Term transform(RuleAutomatonDisjunction ruleAutomatonDisjunction) {
                return (Term) ruleAutomatonDisjunction.disjunctions().stream()
                        .filter(p -> p.getRight().get(i))
                        .map(Pair::getLeft)
                        .findAny().get()
                        .accept(this);
            }

            @Override
            public Term transform(KItem kItem) {
                if (!kItem.kLabel().toString().equals(KLabels.KREWRITE)) {
                    return (Term) super.transform(kItem);
                }

                return (Term) ((Term) kItem.klist().items().get(0)).accept(this);
            }
        });
    }

    private Term getRightHandSide(Term pattern, int i) {
        boolean[] hasRewrite = {false};
        Term result = (Term) pattern.accept(new CopyOnWriteTransformer(null) {
            @Override
            public Term transform(RuleAutomatonDisjunction ruleAutomatonDisjunction) {
                return (Term) ruleAutomatonDisjunction.disjunctions().stream()
                        .filter(p -> p.getRight().get(i))
                        .map(Pair::getLeft)
                        .findAny().get()
                        .accept(this);
            }

            @Override
            public Term transform(KItem kItem) {
                if (!kItem.kLabel().toString().equals(KLabels.KREWRITE)) {
                    return (Term) super.transform(kItem);
                }

                hasRewrite[0] = true;
                return ((InnerRHSRewrite) kItem.klist().items().get(1)).theRHS[i];
            }
        });
        return hasRewrite[0] ? result : null;
    }

    private BitSet unifyMapModuloPatternFolding(BuiltinMap map, BuiltinMap otherMap, BitSet ruleMask, scala.collection.immutable.List<Pair<Integer, Integer>> path) {
        if (!patternFold) {
            return unifyMap(map, otherMap, ruleMask, path);
        }

        Set<BuiltinMap> foldedMaps = Sets.newLinkedHashSet();
        foldedMaps.add(map);
        Queue<BuiltinMap> queue = new LinkedList<>();
        queue.add(map);
        while (!queue.isEmpty()) {
            BuiltinMap candidate = queue.remove();
            for (Rule rule : global.getDefinition().patternFoldingRules()) {
                for (Substitution<Variable, Term> substitution : PatternMatcher.match(candidate, rule, context)) {
                    BuiltinMap result = (BuiltinMap) rule.rightHandSide().substituteAndEvaluate(substitution, context);
                    if (foldedMaps.add(result)) {
                        queue.add(result);

                        ConjunctiveFormula resultConstraint = FastRuleMatcher.unify(result, otherMap, context).simplify();
                        if (resultConstraint.isFalse()) {
                            continue;
                        }

                        /* since here we have a non-deterministic choice to make, we only make
                         * a choice if it eliminates all map equalities */
                        if (!resultConstraint.hasMapEqualities()) {
                            constraints[0] = constraints[0].add(resultConstraint);
                            if (continuousSimplification) {
                                constraints[0] = constraints[0].simplify();
                            }
                            return ruleMask;
                        }
                    }
                }
            }
        }

        /* no folding occurred */
        if (foldedMaps.size() == 1) {
            return unifyMap(map, otherMap, ruleMask, path);
        }

        /* made no progress */
        return addUnification(map, otherMap, ruleMask, path);
    }

    private BitSet unifyMap(BuiltinMap map, BuiltinMap otherMap, BitSet ruleMask, scala.collection.immutable.List<Pair<Integer, Integer>> path) {
        assert map.collectionFunctions().isEmpty() && otherMap.collectionFunctions().isEmpty();

        Map<Term, Term> entries = map.getEntries();
        Map<Term, Term> otherEntries = otherMap.getEntries();
        Set<Term> commonKeys = Sets.intersection(map.getEntries().keySet(), otherEntries.keySet());
        Map<Term, Term> remainingEntries = new HashMap<>();
        Map<Term, Term> otherRemainingEntries = new HashMap<>();
        for (Term key : commonKeys) {
            match(entries.get(key), otherEntries.get(key), ruleMask, path);
        }
        for (Term key : entries.keySet()) {
            if (!commonKeys.contains(key)) {
                remainingEntries.put(key, entries.get(key));
            }
        }
        for (Term key : otherEntries.keySet()) {
            if (!commonKeys.contains(key)) {
                otherRemainingEntries.put(key, otherEntries.get(key));
            }
        }

        Multiset<KItem> patterns = map.collectionPatterns();
        Multiset<KItem> otherPatterns = otherMap.collectionPatterns();
        Set<KItem> unifiedPatterns = new HashSet<>();
        Set<KItem> otherUnifiedPatterns = new HashSet<>();
        List<KItem> remainingPatterns = new ArrayList<>();
        List<KItem> otherRemainingPatterns = new ArrayList<>();
        for (KItem pattern : patterns) {
            for (KItem otherPattern : otherPatterns) {
                if (pattern.kLabel().equals(otherPattern.kLabel())
                        && pattern.getPatternInput().equals(otherPattern.getPatternInput())) {
                    List<Term> patternOutput = pattern.getPatternOutput();
                    List<Term> otherPatternOutput = otherPattern.getPatternOutput();
                    for (int i = 0; i < patternOutput.size(); ++i) {
                        match(patternOutput.get(i), otherPatternOutput.get(i), ruleMask, path);
                    }
                    unifiedPatterns.add(pattern);
                    otherUnifiedPatterns.add(otherPattern);
                }
            }
        }
        for (KItem pattern : patterns) {
            if (!unifiedPatterns.contains(pattern)) {
                remainingPatterns.add(pattern);
            }
        }
        for (KItem otherPattern : otherPatterns) {
            if (!otherUnifiedPatterns.contains(otherPattern)) {
                otherRemainingPatterns.add(otherPattern);
            }
        }

        Multiset<Variable> variables = map.collectionVariables();
        Multiset<Variable> otherVariables = otherMap.collectionVariables();
        Multiset<Variable> commonVariables = Multisets.intersection(variables, otherVariables);
        Multiset<Variable> remainingVariables = Multisets.difference(variables, commonVariables);
        Multiset<Variable> otherRemainingVariables = Multisets.difference(otherVariables, commonVariables);

        if (remainingEntries.isEmpty()
                && remainingPatterns.isEmpty()
                && remainingVariables.isEmpty()
                && !otherRemainingEntries.isEmpty()) {
            //fail(map, otherMap);
            return empty;
        }
        if (otherRemainingEntries.isEmpty()
                && otherRemainingPatterns.isEmpty()
                && otherRemainingVariables.isEmpty()
                && !remainingEntries.isEmpty()) {
            //fail(map, otherMap);
            return empty;
        }

        BuiltinMap.Builder builder = BuiltinMap.builder(global);
        builder.putAll(remainingEntries);
        builder.concatenate(remainingPatterns.toArray(new Term[remainingPatterns.size()]));
        builder.concatenate(remainingVariables.toArray(new Term[remainingVariables.size()]));
        Term remainingMap = builder.build();

        BuiltinMap.Builder otherBuilder = BuiltinMap.builder(global);
        otherBuilder.putAll(otherRemainingEntries);
        otherBuilder.concatenate(otherRemainingPatterns.toArray(new Term[otherRemainingPatterns.size()]));
        otherBuilder.concatenate(otherRemainingVariables.toArray(new Term[otherRemainingVariables.size()]));
        Term otherRemainingMap = otherBuilder.build();

        if (!(remainingMap instanceof BuiltinMap && ((BuiltinMap) remainingMap).isEmpty())
                || !(otherRemainingMap instanceof BuiltinMap && ((BuiltinMap) otherRemainingMap).isEmpty())) {
            if (remainingMap instanceof Variable || otherRemainingMap instanceof Variable || partialSimplification) {
                // map equality resolved or partial simplification enabled
                return addUnification(remainingMap, otherRemainingMap, ruleMask, path);
            } else {
                /* unable to dissolve the entire map equality; thus, we need to
                 * preserve the original map terms for pattern folding */
                return addUnification(map, otherMap, ruleMask, path);
            }
        }

        return ruleMask;
    }

    /**
     * Left-hand side of a minimal equality causing this unification to fail.
     * Must be set if this unification fails.
     */
    private Term unificationFailureLeftHandSide = BoolToken.TRUE;
    /**
     * Right-hand side of a minimal equality causing this unification to fail.
     * Must be set if this unification fails.
     */
    private Term unificationFailureRightHandSide = BoolToken.FALSE;

    public Term unificationFailureLeftHandSide() {
        return unificationFailureLeftHandSide;
    }

    public Term unificationFailureRightHandSide() {
        return unificationFailureRightHandSide;
    }

}
