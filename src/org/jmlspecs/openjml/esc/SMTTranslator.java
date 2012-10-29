/*
 * This file is part of the OpenJML project. 
 * Author: David R. Cok
 */
package org.jmlspecs.openjml.esc;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.jmlspecs.openjml.JmlPretty;
import org.jmlspecs.openjml.JmlToken;
import org.jmlspecs.openjml.JmlTree.*;
import org.jmlspecs.openjml.JmlTreeScanner;
import org.smtlib.ICommand;
import org.smtlib.ICommand.IScript;
import org.smtlib.IExpr;
import org.smtlib.IExpr.IDeclaration;
import org.smtlib.ISort;
import org.smtlib.command.C_assert;
import org.smtlib.command.C_check_sat;
import org.smtlib.command.C_declare_fun;
import org.smtlib.command.C_declare_sort;
import org.smtlib.command.C_define_fun;
import org.smtlib.command.C_set_logic;
import org.smtlib.command.C_set_option;
import org.smtlib.impl.Factory;
import org.smtlib.impl.Script;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ArrayType;
import com.sun.tools.javac.code.TypeTags;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Names;

/** This class translates a BasicBlock program into SMTLIB. 
 * The input program is a BasicBlock program, which may consist of the
 * following kinds of statements:
 * <UL>
 * <LI>declaration statements with or without initializers (FIXME - what kinds of types)
 * <LI>JML assume statements
 * <LI>JML assert statements
 * <LI>JML comment statements
 * </UL>
 * Expressions may include the following:
 * <UL>
 * <LI>Java operators: + - * / % comparisons bit-operations logic-operators
 * <LI>field access - FIXME?
 * <LI>array access - FIXME?
 * <LI>STORE and SELECT functions using Java method class (JCMethodInvocation)
 * <LI>method calls (FIXME - any restrictions?)
 * </UL>
 */
public class SMTTranslator extends JmlTreeScanner {

    /** The error log */
    protected Log log;
    
    /** The symbol table for this compilation context */
    protected Symtab syms;
    
    /** The Names table for this compilation context */
    protected Names names;
    
    /** The factory for creating SMTLIB expressions */
    protected Factory F;
    
    /** SMTLIB subexpressions - the result of each visit call */
    protected IExpr result;
    
    /** Commonly used SMTLIB expressions - using these shares structure */
    protected ISort refSort;
    protected ISort intSort;
    protected ISort boolSort;
    protected IExpr.ISymbol nullRef;
    protected IExpr.ISymbol lengthRef;
    protected IExpr.ISymbol thisRef;
    
    
    /** The SMTLIB script as it is being constructed */
    protected IScript script;
    
    /** An alias to script.commands() */
    protected List<ICommand> commands;
    
    // Strings used in our use of SMT. Strings that are part of SMTLIB itself
    // are used verbatim in the code.
    public static final String store = "store";
    public static final String select = "select";
    public static final String NULL = "NULL";
    public static final String this_ = "this";
    public static final String REF = "REF";
    public static final String length = "length";
    
    
    public SMTTranslator(Context context) {
        log = Log.instance(context);
        syms = Symtab.instance(context);
        names = Names.instance(context);
        F = new org.smtlib.impl.Factory();
        nullRef = F.symbol(NULL);
        thisRef = F.symbol(this_);
        refSort = F.createSortExpression(F.symbol(REF));
        lengthRef = F.symbol(length);
    }
    
    // FIXME - want to be able to produce AUFBV programs as well
    // FIXME - this converts the whole program into one big SMT program
    //  - might want the option to produce many individual programs, i.e.
    //  one for each assertion, or a form that accommodates push/pop/coreids etc.
    
    public ICommand.IScript convert(BasicProgram program) {
        script = new Script();
        ICommand c;
        commands = script.commands();
        
        // set the logic
        c = new C_set_option(F.keyword(":produce-models"),F.symbol("true"));
        commands.add(c);
        c = new C_set_logic(F.symbol("AUFLIRA"));
        commands.add(c);
        
        // add background statements
        c = new C_declare_sort(F.symbol(REF),F.numeral(0));
        commands.add(c);
        c = new C_declare_fun(nullRef,new LinkedList<ISort>(), refSort);
        commands.add(c);
        c = new C_declare_fun(thisRef,new LinkedList<ISort>(), refSort);
        commands.add(c);
        c = new C_assert(F.fcn(F.symbol("distinct"), thisRef, nullRef));
        commands.add(c);
        List<ISort> args = new LinkedList<ISort>();
        c = new C_declare_fun(lengthRef,
                args, 
                F.createSortExpression(F.symbol("Array"),
                refSort,
                F.createSortExpression(F.symbol("Int"))));
        commands.add(c);
        args = new LinkedList<ISort>();
        args.add(refSort);
        c = new C_declare_fun(F.symbol("asIntArray"),args, F.createSortExpression(F.symbol("Array"),F.createSortExpression(F.symbol("Int")),F.createSortExpression(F.symbol("Int"))));
        commands.add(c);
        c = new C_declare_fun(F.symbol("asRefArray"),args, F.createSortExpression(F.symbol("Array"),F.createSortExpression(F.symbol("Int")),refSort));
        commands.add(c);
        c = new C_declare_fun(F.symbol("intValue"),args, F.createSortExpression(F.symbol("Int")));
        commands.add(c);
        c = new C_declare_fun(F.symbol("boolValue"),args, F.createSortExpression(F.symbol("Bool")));
        commands.add(c);
        
        for (JCExpression e: program.background()) {
            try {
                e.accept(this);
                IExpr ex = result;
                c = new C_assert(ex);
                commands.add(c);
            } catch (RuntimeException ee) {
                // skip - error already issued // FIXME - better error recovery?
            }
        }
        
        // add declarations
        
        for (JCIdent id: program.declarations) {
            try {
                ISort sort = convertSort(id.type);
                if (id.sym.owner instanceof Symbol.ClassSymbol && !id.sym.isStatic() && !id.sym.name.toString().equals("this")) {
                    sort = F.createSortExpression(F.symbol("Array"),refSort,sort);
                }
                c = new C_declare_fun(F.symbol(id.name.toString()),
                        new LinkedList<ISort>(),
                        sort);
                commands.add(c);
            } catch (RuntimeException ee) {
                // skip - error already issued// FIXME - better error recovery?
            }
        }
        
        // add definitions
        for (BasicProgram.Definition e: program.definitions()) {
            try {
                e.value.accept(this);
                c = new C_define_fun(F.symbol(e.id.toString()),
                        new LinkedList<IDeclaration>(),
                        convertSort(e.id.type),
                        result);
                commands.add(c);
            } catch (RuntimeException ee) {
                // skip - error already issued // FIXME - better error recovery?
            }
        }
        
        // Because blocks have forward references to later blocks, but
        // backward references to variables in earlier blocks, we declare
        // all the block variables first
        for (BasicProgram.BasicBlock b: program.blocks()) {
            ICommand cc = new C_declare_fun(F.symbol(b.id.toString()), new LinkedList<ISort>(), F.Bool());
            commands.add(cc);
        }
        
        // add blocks
        for (BasicProgram.BasicBlock b: program.blocks()) {
            convertBasicBlock(b);
        }
        
        LinkedList<IExpr> argss = new LinkedList<IExpr>();
        argss.add(F.symbol(program.startId().name.toString()));
        IExpr negStartID = F.fcn(F.symbol("not"), argss);
        ICommand cc = new C_assert(negStartID);
        commands.add(cc);
        
        cc = new C_check_sat();
        commands.add(cc);
        
        return script;
    }
    
    /** Converts a BasicBlock into SMTLIB, adding commands into the
     * current 'commands' list.
     */
    public void convertBasicBlock(BasicProgram.BasicBlock block) {
        Iterator<JCStatement> iter = block.statements.iterator();
        IExpr tail; 
        if (block.succeeding.isEmpty()) {
            tail = F.symbol("true");
        } else if (block.succeeding.size() == 1) {
            tail = F.symbol(block.succeeding.get(0).id.name.toString());
        } else {
            ArrayList<IExpr> args = new ArrayList<IExpr>();
            for (BasicProgram.BasicBlock bb: block.succeeding) {
                args.add(F.symbol(bb.id.name.toString()));
            }
            tail = F.fcn(F.symbol("and"),args);
        }
        IExpr ex = convert(iter,tail);
        LinkedList<IExpr> args = new LinkedList<IExpr>();
        args.add(F.symbol(block.id.toString()));
        args.add(ex);
        ex = F.fcn(F.symbol("="),args);
        commands.add(new C_assert(ex));
    }
    
    /** A helper method for convertBasicBlock. We need to construct the
     * expression representing a basicBlock from the end back to the 
     * beginning; we use recursive calls on this method to do that.
     */
    public IExpr convert(Iterator<JCStatement> iter, IExpr tail) {
        if (!iter.hasNext()) {
            return tail;
        }
        JCStatement stat = iter.next();
        try {
            if (stat instanceof JCVariableDecl) {
                JCVariableDecl decl = (JCVariableDecl)stat;
                // convert to a declaration or definition
                IExpr init = decl.init == null ? null : convertExpr(decl.init);
                
                ICommand c = init == null ?
                        new C_declare_fun(
                                F.symbol(decl.name.toString()),
                                new LinkedList<ISort>(),
                                convertSort(decl.type))
                : new C_define_fun(
                        F.symbol(decl.name.toString()),
                        new LinkedList<IDeclaration>(),
                        convertSort(decl.type),
                        init);
                 commands.add(c);
                 return convert(iter,tail);
            } else if (stat instanceof JmlStatementExpr) {
                IExpr ex = convert(iter,tail);
                JmlStatementExpr s = (JmlStatementExpr)stat;
                if (s.token == JmlToken.ASSUME) {
                    IExpr exx = convertExpr(s.expression);
                    LinkedList<IExpr> args = new LinkedList<IExpr>();
                    args.add(exx);
                    args.add(ex);
                    return F.fcn(F.symbol("=>"), args);
                } else if (s.token == JmlToken.ASSERT) {
                    IExpr exx = convertExpr(s.expression);
                    LinkedList<IExpr> args = new LinkedList<IExpr>();
                    args.add(exx);
                    args.add(ex);
                    return F.fcn(F.symbol("and"), args);
                } else if (s.token == JmlToken.COMMENT) {
                    // skip - add script comment ? TODO
                    return ex;
                } else {
                    log.error("jml.internal", "Incorrect kind of token encountered when converting a BasicProgram to SMTLIB: " + s.token);
                    return ex;
                }
            } else {
                log.error("jml.internal", "Incorrect kind of statement encountered when converting a BasicProgram to SMTLIB: " + stat.getClass());
            }
        } catch (RuntimeException ee) {
            // skip - error already issued // FIXME - better recovery
        }
        return F.symbol("false");
        
    }
    
    // FIXME - review this
    /** Converts a Java/JML type into an SMT Sort */
    public ISort convertSort(Type t) {
        if ( t == null) {
            log.error("jml.internal", "No type translation implemented when converting a BasicProgram to SMTLIB: " + t);
            throw new RuntimeException();
        } else if (t.equals(syms.booleanType)) {
            return F.Bool();
        } else if (t.tsym == syms.intType.tsym) { 
            return F.createSortExpression(F.symbol("Int"));
        } else if (t.tag == syms.objectType.tag) {
            return refSort;
        } else if (t instanceof ArrayType) {
            return refSort;
//            ArrayType atype = (ArrayType)t;
//            Type elemtype = atype.getComponentType();
//            return F.createSortExpression(F.symbol("Array"), F.createSortExpression(F.symbol("Int")), convertSort(elemtype));
        } else {
            log.error("jml.internal", "No type translation implemented when converting a BasicProgram to SMTLIB: " + t);
            throw new RuntimeException();
        }
    }
    
    /** Converts an AST expression into SMT form. */
    public IExpr convertExpr(JCExpression expr) {
        expr.accept(this);
        return result;
    }
    
    // We need to be able to translate expressions
    
    public void notImpl(JCTree tree) {
        log.error("jml.internal","Not yet supported expression node in converting BasicPrograms to SMTLIB: " + tree.getClass());
    }
    
    public void shouldNotBeCalled(JCTree tree) {
        log.error("jml.internal","This node should not be present in converting BasicPrograms to SMTLIB: " + tree.getClass());
    }
    
    @Override
    public void visitApply(JCMethodInvocation tree) {
        JCExpression m = tree.meth;
        if (m instanceof JCIdent) {
            if (((JCIdent)m).name.toString().equals(BasicBlocker2.STOREString)) {
                result = F.fcn(F.symbol("store"),
                        convertExpr(tree.args.get(0)),
                        convertExpr(tree.args.get(1)),
                        convertExpr(tree.args.get(2))
                        );
                return;
            }
        } else if (m == null) {
            if (tree instanceof JmlBBFieldAssignment) {
                result = F.fcn(F.symbol("store"),
                        convertExpr(tree.args.get(1)),
                        convertExpr(tree.args.get(2)),
                        convertExpr(tree.args.get(3))
                        );
                result = F.fcn(F.symbol("="), convertExpr(tree.args.get(0)),result);
                return;
            }
        }
        notImpl(tree);
        super.visitApply(tree);
    }
    
    @Override
    public void visitJmlMethodInvocation(JmlMethodInvocation that) {
        // FIXME - I think this should not be called?
        List<IExpr> newargs = new LinkedList<IExpr>();
        for (JCExpression e: that.args) {
            scan(e);
            newargs.add(result);
        }
        if (that.meth != null) result = F.fcn(F.symbol(that.meth.toString()),newargs);
        else result = newargs.get(0); // FIXME - this is needed for \old and \pre but a better solution should be found (cf. testLabeled)
    }

    @Override
    public void visitNewClass(JCNewClass tree) {
        shouldNotBeCalled(tree);
        super.visitNewClass(tree);
    }

    @Override
    public void visitNewArray(JCNewArray tree) {
        shouldNotBeCalled(tree);
        super.visitNewArray(tree);
    }

    @Override
    public void visitAssign(JCAssign tree) {
        shouldNotBeCalled(tree);
        super.visitAssign(tree);
    }

    @Override
    public void visitAssignop(JCAssignOp tree) {
        shouldNotBeCalled(tree);
        super.visitAssignop(tree);
    }

    @Override
    public void visitUnary(JCUnary tree) {
        int op = tree.getTag();
        tree.arg.accept(this);
        IExpr arg = result;
        LinkedList<IExpr> args = new LinkedList<IExpr>();
        args.add(arg);
        switch (op) {
            case JCTree.NOT:
                result = F.fcn(F.symbol("not"), args);
                break;
            case JCTree.NEG:
                result = F.fcn(F.symbol("-"), args);
                break;
            default:
                log.error("jml.internal","Don't know how to translate expression to SMTLIB: " + JmlPretty.write(tree));
                throw new RuntimeException();
        }
    }
    
    @Override public void visitParens(JCParens that) {
        // Since SMT-LIB consists of S-expressions, we do not need to 
        // add additional parentheses for resolving precedence
        super.visitParens(that);
    }


    @Override
    public void visitBinary(JCBinary tree) {
        int op = tree.getTag();
        tree.lhs.accept(this);
        IExpr lhs = result;
        tree.rhs.accept(this);
        IExpr rhs = result;
        LinkedList<IExpr> args = new LinkedList<IExpr>();
        args.add(lhs);
        args.add(rhs);
        switch (op) {
            case JCTree.EQ:
                result = F.fcn(F.symbol("="), args);
                break;
            case JCTree.NE:
                result = F.fcn(F.symbol("distinct"), args);
                break;
            case JCTree.AND:
                result = F.fcn(F.symbol("and"), args);
                break;
            case JCTree.OR:
                result = F.fcn(F.symbol("or"), args);
                break;
            case JCTree.LT:
                result = F.fcn(F.symbol("<"), args);
                break;
            case JCTree.LE:
                result = F.fcn(F.symbol("<="), args);
                break;
            case JCTree.GT:
                result = F.fcn(F.symbol(">"), args);
                break;
            case JCTree.GE:
                result = F.fcn(F.symbol(">="), args);
                break;
            case JCTree.PLUS:
                result = F.fcn(F.symbol("+"), args);
                break;
            case JCTree.MINUS:
                result = F.fcn(F.symbol("-"), args);
                break;
            case JCTree.MUL:
                result = F.fcn(F.symbol("*"), args);
                break;
            case JCTree.DIV:
                // FIXME - what kinds of primitive types should be expected
                if (tree.type.tag == TypeTags.FLOAT)
                    result = F.fcn(F.symbol("/"), args);
                else if (tree.type.tag == TypeTags.DOUBLE)
                    result = F.fcn(F.symbol("/"), args);
                else
                    result = F.fcn(F.symbol("div"), args);
                break;
            case JCTree.MOD:
                result = F.fcn(F.symbol("mod"), args);
                break;
                // FIXME - implement these
//            case JCTree.SL:
//                result = F.fcn(F.symbol("or"), args);
//                break;
//            case JCTree.SR:
//                result = F.fcn(F.symbol("or"), args);
//                break;
//            case JCTree.USR:
//                result = F.fcn(F.symbol("or"), args);
//                break;
//            case JCTree.BITAND:
//                result = F.fcn(F.symbol("or"), args);
//                break;
//            case JCTree.BITOR:
//                result = F.fcn(F.symbol("or"), args);
//                break;
//            case JCTree.BITXOR:
//                result = F.fcn(F.symbol("or"), args);
//                break;
            default:
                log.error("jml.internal","Don't know how to translate expression to SMTLIB: " + JmlPretty.write(tree));
                throw new RuntimeException();
        }
    }

    @Override
    public void visitTypeCast(JCTypeCast tree) {
        notImpl(tree); // TODO
        super.visitTypeCast(tree);
    }

    @Override
    public void visitTypeTest(JCInstanceOf tree) {
        notImpl(tree); // TODO
        super.visitTypeTest(tree);
    }

    @Override
    public void visitIndexed(JCArrayAccess tree) {
        scan(tree.indexed);
        IExpr array = result;
        scan(tree.index);
        IExpr index = result;
        if (tree.type.tag == syms.intType.tag) {
            result = F.fcn(F.symbol("asIntArray"), array);
            result = F.fcn(F.symbol("select"),result,index);
        } else if (!tree.type.isPrimitive()) {
            result = F.fcn(F.symbol("asRefArray"), array);
            result = F.fcn(F.symbol("select"),result,index);
        } else {
            notImpl(tree);
            result = null;
        }
    }

    @Override
    public void visitSelect(JCFieldAccess tree) {
        // FIXME - review
        // o.f becomes f[o] where f has sort (Array REF type)
        if (tree.selected != null) doFieldAccess(tree.selected,tree.sym);
    }
    
    // FIXME - review
    protected void doFieldAccess(JCExpression object, Symbol field) {
        if (field != syms.lengthVar) {
            ISort arrsort = F.createSortExpression(F.symbol("Array"),refSort,convertSort(field.type));
            List<ISort> args = new LinkedList<ISort>();
            ICommand c = new C_declare_fun(F.symbol(field.name.toString()),
                    args,arrsort);
            commands.add(c);
        }
        result = F.fcn(F.symbol("select"),F.symbol(field.name.toString()),
                object == null ? thisRef: convertExpr(object));
        
    }

    @Override
    public void visitIdent(JCIdent tree) {
        if (tree.sym != null && tree.sym.owner instanceof ClassSymbol && tree.sym.name != names._this && !tree.sym.isStatic()) {
            // a select from this
            doFieldAccess(null,tree.sym);
        } else {
            result = F.symbol(tree.name.toString());
        } 
    }

    @Override
    public void visitLiteral(JCLiteral tree) {
        // FIXME - need real, double, char, byte
        if (tree.typetag == TypeTags.BOOLEAN) {
           result = F.symbol(((Boolean)tree.getValue()) ?"true":"false"); 
        } else if (tree.typetag == TypeTags.INT) {
            result = F.numeral(Integer.parseInt(tree.toString()));
        } else if (tree.typetag == TypeTags.LONG) {
            result = F.numeral(Integer.parseInt(tree.toString()));
        } else if (tree.typetag == TypeTags.SHORT) {
            result = F.numeral(Integer.parseInt(tree.toString()));
        } else if (tree.typetag == TypeTags.BOT) {
            result = nullRef;
        } else {
            notImpl(tree);
            super.visitLiteral(tree);
        }
    }

    @Override public void visitJmlPrimitiveTypeTree(JmlPrimitiveTypeTree that) { notImpl(that); } // FIXME - maybe
    @Override public void visitJmlQuantifiedExpr(JmlQuantifiedExpr that)     { notImpl(that); } // FIXME - not impl
    @Override public void visitJmlSetComprehension(JmlSetComprehension that) { notImpl(that); }
    @Override public void visitJmlSingleton(JmlSingleton that)               { notImpl(that); }

//    public void visitJmlQuantifiedExpr(JmlQuantifiedExpr that) {
//        List<IDeclaration> params = new LinkedList<IDeclaration>();
//        for (JCVariableDecl decl: that.decls) {
//            IExpr.ISymbol sym = F.symbol(decl.name.toString());
//            ISort sort = convertSort(decl.type);
//            params.add(F.declaration(sym, sort));
//        }
//        scan(that.decls);
//        scan(that.range);
//        scan(that.value);
//        scan(that.racexpr);
//    }
//
//    public void visitJmlSetComprehension(JmlSetComprehension that) {
//        scan(that.newtype);
//        scan(that.variable);
//        scan(that.predicate);
//    }
//
//    public void visitJmlLblExpression(JmlLblExpression that) {
//        scan(that.expression);
//    }
//


    @Override
    public void visitTypeIdent(JCPrimitiveTypeTree tree) {
        notImpl(tree);
        super.visitTypeIdent(tree);
    }

    @Override
    public void visitTypeArray(JCArrayTypeTree tree) {
        notImpl(tree);
        super.visitTypeArray(tree);
    }

    @Override
    public void visitTypeApply(JCTypeApply tree) {
        notImpl(tree);
        super.visitTypeApply(tree);
    }

    @Override
    public void visitTypeUnion(JCTypeUnion tree) {
        notImpl(tree);
        super.visitTypeUnion(tree);
    }

    @Override
    public void visitTypeParameter(JCTypeParameter tree) {
        notImpl(tree);
        super.visitTypeParameter(tree);
    }

    @Override
    public void visitWildcard(JCWildcard tree) {
        notImpl(tree);
        super.visitWildcard(tree);
    }

    @Override
    public void visitTypeBoundKind(TypeBoundKind tree) {
        notImpl(tree);
        super.visitTypeBoundKind(tree);
    }
    
    // These should all be translated away prior to calling the basic blocker,
    // or should never be called in the first place, because they are not
    // expressions
    // FIXME - what about calls of anonymous classes
    @Override public void visitTopLevel(JCCompilationUnit that)    { shouldNotBeCalled(that); }
    @Override public void visitImport(JCImport that)               { shouldNotBeCalled(that); }
    @Override public void visitJmlCompilationUnit(JmlCompilationUnit that)   { shouldNotBeCalled(that); }
    @Override public void visitJmlImport(JmlImport that)                     { shouldNotBeCalled(that); }
    @Override public void visitMethodDef(JCMethodDecl that)        { shouldNotBeCalled(that); }
    @Override public void visitJmlMethodDecl(JmlMethodDecl that)  { shouldNotBeCalled(that); }
    @Override public void visitJmlBinary(JmlBinary that)           { shouldNotBeCalled(that); }
    @Override public void visitJmlChoose(JmlChoose that)           { shouldNotBeCalled(that); }
    @Override public void visitJmlClassDecl(JmlClassDecl that)           { shouldNotBeCalled(that); }
    @Override public void visitJmlConstraintMethodSig(JmlConstraintMethodSig that) { shouldNotBeCalled(that); }
    @Override public void visitJmlDoWhileLoop(JmlDoWhileLoop that)  { shouldNotBeCalled(that); }
    @Override public void visitJmlEnhancedForLoop(JmlEnhancedForLoop that) { shouldNotBeCalled(that); }
    @Override public void visitJmlForLoop(JmlForLoop that) { shouldNotBeCalled(that); }
    @Override public void visitJmlGroupName(JmlGroupName that) { shouldNotBeCalled(that); }
    @Override public void visitJmlLblExpression(JmlLblExpression that) { shouldNotBeCalled(that); }    
    @Override public void visitJmlMethodClauseCallable(JmlMethodClauseCallable that) { shouldNotBeCalled(that); }
    @Override public void visitJmlMethodClauseConditional(JmlMethodClauseConditional that) { shouldNotBeCalled(that); }
    @Override public void visitJmlMethodClauseDecl(JmlMethodClauseDecl that) { shouldNotBeCalled(that); }
    @Override public void visitJmlMethodClauseExpr(JmlMethodClauseExpr that) { shouldNotBeCalled(that); }
    @Override public void visitJmlMethodClauseGroup(JmlMethodClauseGroup that) { shouldNotBeCalled(that); }
    @Override public void visitJmlMethodClauseSignals(JmlMethodClauseSignals that) { shouldNotBeCalled(that); }
    @Override public void visitJmlMethodClauseSigOnly(JmlMethodClauseSignalsOnly that) { shouldNotBeCalled(that); }
    @Override public void visitJmlMethodClauseStoreRef(JmlMethodClauseStoreRef that) { shouldNotBeCalled(that); }
    @Override public void visitJmlMethodSpecs(JmlMethodSpecs that)           { shouldNotBeCalled(that); }
    @Override public void visitJmlModelProgramStatement(JmlModelProgramStatement that) { shouldNotBeCalled(that); }
    @Override public void visitJmlSpecificationCase(JmlSpecificationCase that) { shouldNotBeCalled(that); }
    @Override public void visitJmlStatementDecls(JmlStatementDecls that) { shouldNotBeCalled(that); }
    @Override public void visitJmlStatementExpr(JmlStatementExpr that) { shouldNotBeCalled(that); }
    @Override public void visitJmlStatementLoop(JmlStatementLoop that) { shouldNotBeCalled(that); }
    @Override public void visitJmlStatementSpec(JmlStatementSpec that) { shouldNotBeCalled(that); }
    @Override public void visitJmlStoreRefArrayRange(JmlStoreRefArrayRange that) { shouldNotBeCalled(that); }
    @Override public void visitJmlStoreRefKeyword(JmlStoreRefKeyword that) { shouldNotBeCalled(that); }
    @Override public void visitJmlStoreRefListExpression(JmlStoreRefListExpression that) { shouldNotBeCalled(that); }
    @Override public void visitJmlTypeClauseConditional(JmlTypeClauseConditional that) { shouldNotBeCalled(that); }
    @Override public void visitJmlTypeClauseConstraint(JmlTypeClauseConstraint that) { shouldNotBeCalled(that); }
    @Override public void visitJmlTypeClauseDecl(JmlTypeClauseDecl that) { shouldNotBeCalled(that); }
    @Override public void visitJmlTypeClauseExpr(JmlTypeClauseExpr that) { shouldNotBeCalled(that); }
    @Override public void visitJmlTypeClauseIn(JmlTypeClauseIn that) { shouldNotBeCalled(that); }
    @Override public void visitJmlTypeClauseInitializer(JmlTypeClauseInitializer that) { shouldNotBeCalled(that); }
    @Override public void visitJmlTypeClauseMaps(JmlTypeClauseMaps that) { shouldNotBeCalled(that); }
    @Override public void visitJmlTypeClauseMonitorsFor(JmlTypeClauseMonitorsFor that) { shouldNotBeCalled(that); }
    @Override public void visitJmlTypeClauseRepresents(JmlTypeClauseRepresents that) { shouldNotBeCalled(that); }
    @Override public void visitJmlVariableDecl(JmlVariableDecl that) { shouldNotBeCalled(that); }
    @Override public void visitJmlWhileLoop(JmlWhileLoop that) { shouldNotBeCalled(that); }

    @Override public void visitClassDef(JCClassDecl that) { shouldNotBeCalled(that); }
    @Override public void visitVarDef(JCVariableDecl that)  { shouldNotBeCalled(that); }
    @Override public void visitSkip(JCSkip that) { shouldNotBeCalled(that); }
    @Override public void visitBlock(JCBlock that) { shouldNotBeCalled(that); }
    @Override public void visitDoLoop(JCDoWhileLoop that) { shouldNotBeCalled(that); }
    @Override public void visitWhileLoop(JCWhileLoop that) { shouldNotBeCalled(that); }
    @Override public void visitForLoop(JCForLoop that) { shouldNotBeCalled(that); }
    @Override public void visitForeachLoop(JCEnhancedForLoop that) { shouldNotBeCalled(that); }
    @Override public void visitLabelled(JCLabeledStatement that) { shouldNotBeCalled(that); }
    @Override public void visitSwitch(JCSwitch that) { shouldNotBeCalled(that); }
    @Override public void visitCase(JCCase that) { shouldNotBeCalled(that); }
    @Override public void visitSynchronized(JCSynchronized that) { shouldNotBeCalled(that); }
    @Override public void visitTry(JCTry that) { shouldNotBeCalled(that); }
    @Override public void visitCatch(JCCatch that) { shouldNotBeCalled(that); }
    @Override public void visitConditional(JCConditional that) { shouldNotBeCalled(that); }
    @Override public void visitIf(JCIf that) { shouldNotBeCalled(that); }
    @Override public void visitExec(JCExpressionStatement that) { shouldNotBeCalled(that); }
    @Override public void visitBreak(JCBreak that) { shouldNotBeCalled(that); }
    @Override public void visitContinue(JCContinue that) { shouldNotBeCalled(that); }
    @Override public void visitReturn(JCReturn that) { shouldNotBeCalled(that); }
    @Override public void visitThrow(JCThrow that) { shouldNotBeCalled(that); }
    @Override public void visitAssert(JCAssert that) { shouldNotBeCalled(that); }
 
    // Some of these could be notImpl
    @Override public void visitAnnotation(JCAnnotation that) { shouldNotBeCalled(that); }
    @Override public void visitModifiers(JCModifiers that) { shouldNotBeCalled(that); }
    @Override public void visitErroneous(JCErroneous that) { shouldNotBeCalled(that); }
    @Override public void visitLetExpr(LetExpr that) { shouldNotBeCalled(that); }

}