// Copyright (c) 2012-2014 K Team. All Rights Reserved.
package org.kframework.compile.transformers;

import org.kframework.compile.utils.MetaK;
import org.kframework.kil.*;
import org.kframework.kil.loader.Context;
import org.kframework.kil.visitors.CopyOnWriteTransformer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Class adding #_ : Builtin -> KLabel wrappers and is#Builtin predicates for each builtin.
 *
 * andreis: this is backend specific, should go to MaudeFilter or something...
 */
public class ResolveBuiltins extends CopyOnWriteTransformer {

    private Set<Sort2> builtinSorts = new HashSet<>();

    public ResolveBuiltins(Context context) {
        super("Resolve Builtins", context);
    }

    @Override
    public ASTNode visit(Module node, Void _)  {
        builtinSorts.clear();
        super.visit(node, _);
        if (builtinSorts.isEmpty()) return node;
        node = node.shallowCopy();
        List<ModuleItem> items = new ArrayList<ModuleItem>(node.getItems());
        List<PriorityBlock> priorities = new ArrayList<PriorityBlock>();
        PriorityBlock block = new PriorityBlock();
        priorities.add(block );
        Syntax syn = new Syntax(new Sort(Sort2.KLABEL), priorities);
        items.add(syn);
        for (Sort2 sort : builtinSorts) {
            List<ProductionItem> pItems = new ArrayList<ProductionItem>();
            Production p = new Production(new Sort(Sort2.KLABEL), pItems );
            pItems.add(new Terminal("#"));
            pItems.add(new Sort(sort));
            p.putAttribute("KLabelWrapper", sort.getName());
            p.putAttribute("cons", "KLabel1" + sort + "Wrapper");
            p.putAttribute("prefixlabel", "#_");
            context.conses.put("KLabel1" + sort + "Wrapper", p);
            context.putLabel(p, "KLabel1" + sort+ "Wrapper");
            block.getProductions().add(p);
            pItems = new ArrayList<ProductionItem>();
            p = new Production(new Sort(Sort2.KLABEL), pItems );
            pItems.add(new Terminal("is" + sort));
            block.getProductions().add(p);
            Rule rule = new Rule();
            rule.setBody(new Rewrite(
                    KApp.of(KLabelConstant.of(AddPredicates.predicate(sort.getName()), context),
                            new Variable(sort.getName(), sort)),
                    BoolBuiltin.TRUE, context));
            rule.addAttribute(Attribute.PREDICATE);
            items.add(rule);

        }
        node.setItems(items);
        return node;
    }

    @Override
    public ASTNode visit(Sort node, Void _)  {
        if (Sort2.of(node.getName()).isBuiltinSort())
                builtinSorts.add(node.getSort2());
        return node;
    }

    @Override
    public ASTNode visit(Configuration node, Void _)  {
        return node;
    }

    @Override
    public ASTNode visit(org.kframework.kil.Context node, Void _)  {
        return node;
    }

    @Override
    public ASTNode visit(Rule node, Void _)  {
        return node;
    }

}
