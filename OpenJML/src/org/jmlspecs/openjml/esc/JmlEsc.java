package org.jmlspecs.openjml.esc;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.tools.JavaFileObject;

import org.jmlspecs.annotations.NonNull;
import org.jmlspecs.annotations.Nullable;
import org.jmlspecs.openjml.JmlOptionName;
import org.jmlspecs.openjml.JmlSpecs;
import org.jmlspecs.openjml.JmlToken;
import org.jmlspecs.openjml.JmlTree;
import org.jmlspecs.openjml.JmlTreeScanner;
import org.jmlspecs.openjml.Utils;
import org.jmlspecs.openjml.JmlTree.JmlMethodDecl;
import org.jmlspecs.openjml.JmlTree.JmlMethodSpecs;
import org.jmlspecs.openjml.JmlTree.JmlStatementExpr;
import org.jmlspecs.openjml.esc.BasicBlocker.Counter;
import org.jmlspecs.openjml.esc.BasicProgram.BasicBlock;
import org.jmlspecs.openjml.proverinterface.IProver;
import org.jmlspecs.openjml.proverinterface.IProverResult;
import org.jmlspecs.openjml.proverinterface.ProverException;
import org.jmlspecs.openjml.proverinterface.ProverResult;
import org.jmlspecs.openjml.proverinterface.IProverResult.ICoreIds;
import org.jmlspecs.openjml.proverinterface.IProverResult.ICounterexample;
import org.jmlspecs.openjml.provers.AbstractProver;
import org.jmlspecs.openjml.provers.CVC3Prover;
import org.jmlspecs.openjml.provers.SimplifyProver;
import org.jmlspecs.openjml.provers.YicesProver;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeTags;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.comp.Attr;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCBinary;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Name;

/**
 * This class is the main driver for executing ESC on a Java/JML AST. It
 * formulates the material to be proved, initiates the proofs, and obtains and
 * reports the results. The class is also a TreeScanner so that it can easily
 * walk the tree to find all class and method declarations.
 * 
 * FIXME - describe calling mechanism
 * 
 * @author David R. Cok
 */
public class JmlEsc extends JmlTreeScanner {

    protected static final Context.Key<JmlEsc> escKey =
        new Context.Key<JmlEsc>();

    public static JmlEsc instance(Context context) {
        JmlEsc instance = context.get(escKey);
        if (instance == null)
            instance = new JmlEsc(context);
        return instance;
    }
    
    /** true if compiler options are set to a verbose mode */
    boolean verbose;
    
    /** Just for debugging esc */
    public static boolean escdebug = Utils.jmldebug;
    
    /** true if counterexample information is desired */
    boolean showCounterexample;
    
    /** true if counterexample trace information is desired */
    boolean showTrace;
    
    /** true if subexpression trace information is desired */
    boolean showSubexpressions;
    
    /** The compilation context, needed to get common tools, but unique to this compilation run*/
    @NonNull Context context;

    /** Used to obtain cached symbols, such as basic types */
    @NonNull Symtab syms;
    
    /** The database of JML specifications for methods, classes, fields, ... */
    @NonNull JmlSpecs specs;
    
    /** The names database */
    @NonNull Name.Table names;
    
    /** The factory for making AST nodes */
    @NonNull JmlTree.JmlFactory factory;
    
    /** The tool to log problem reports */ 
    @NonNull Log log;
    
    /** Whether to check that key assumptions are feasible */
    public boolean checkAssumptions = true;


    @NonNull final static public String arraysRoot = "$$arrays";  // Reference in masicblocker?

    /** The tool constructor, which initializes all the tools. */
    public JmlEsc(Context context) {
        this.context = context;
        this.syms = Symtab.instance(context);
        this.specs = JmlSpecs.instance(context);
        this.log = Log.instance(context);
        this.names = Name.Table.instance(context);
        this.factory = (JmlTree.JmlFactory)JmlTree.Maker.instance(context);
        this.verbose = JmlOptionName.isOption(context,"-verbose") ||
            JmlOptionName.isOption(context,JmlOptionName.JMLVERBOSE) || 
            Utils.jmldebug;
        this.showCounterexample = JmlOptionName.isOption(context,"-ce") || JmlOptionName.isOption(context,JmlOptionName.COUNTEREXAMPLE) || JmlOptionName.isOption(context,JmlOptionName.JMLVERBOSE) ; // FIXME - options
        this.showSubexpressions = JmlOptionName.isOption(context,JmlOptionName.SUBEXPRESSIONS);
        this.showTrace = showCounterexample || JmlOptionName.isOption(context,JmlOptionName.TRACE) || showSubexpressions;
        this.checkAssumptions = !JmlOptionName.isOption(context,"-noCheckAssumptions");
    }

    /** Set to the currently owning class declaration while visiting JCClassDecl and its children. */
    @Nullable JCClassDecl currentClassDecl = null;
    
    public void visitClassDef(JCClassDecl node) {
        if (node.sym.isInterface()) return;  // Nothing to verify in an interface
        //System.out.println("DOING CLASS " + node.sym);
        
        // Save the information in case classes are nested
        JCClassDecl prev = currentClassDecl;
        try {
            currentClassDecl = node;
            super.visitClassDef(node);
        } finally {
            currentClassDecl = prev;
        }
    }

    static boolean usePush = true;
    static boolean useRetract = false;
    static boolean useSearch = false;
    static boolean useCoreIds = true;
    static boolean useTree = false;
    //public static boolean mainCheckOnly = false;
    int timingTest;

    /** When we visit a method declaration, we translate and prove the method;
     * we do not walk into the method any further from this call, only through
     * the translation mechanism.
     */  // FIXME - what about local classes or anonymous classes
    public void visitMethodDef(@NonNull JCMethodDecl node) {
        if (!(node instanceof JmlMethodDecl)) {
            log.warning("esc.not.implemented","Unexpected non-JmlMethodDecl in JmlEsc - not checking " + node.sym);
            return;
        }
        boolean isConstructor = node.getReturnType() == null;
        boolean doEsc = ((node.mods.flags & (Flags.SYNTHETIC|Flags.ABSTRACT|Flags.NATIVE)) == 0);

        // Don't do ESC on the constructor of Object
        // FIXME - why?  (we don't have the source anyway, so how would we get here?)
        if (currentClassDecl.sym == syms.objectType.tsym && isConstructor) doEsc = false;
        if (!doEsc) return;

        String name = node.sym.owner + "." + node.sym;
//        Log.printLines(log.noticeWriter,"["+(++ord)+"] "+ "Checking method "+ name);
//        if (true) return;
        
        Pattern doPattern = 
            null; 
        //Pattern.compile("escjava[.]ast[.]ArrayRangeRefExpr[.]getTag[(].*"); 
        //Pattern.compile("escjava[.]sortedProver[.]Lifter[.]FnTerm[.]dump[(].*"); 
        Pattern[] avoids = {
                Pattern.compile(".*anonymous.*"),

//                Pattern.compile("escjava[.]sortedProver[.]Lifter[.]FnTerm[.]printTo[(].*"), // too much time
//                Pattern.compile("escjava[.]sortedProver[.]Lifter[.]Term[.]toString[(].*"), // too much time
//                Pattern.compile("escjava[.]sortedProver[.]Lifter[.]Term[.]printTo[(].*"), // too much time
//                Pattern.compile("escjava[.]sortedProver[.]Lifter[.]QuantVariableRef[.]printTo[(].*"), // too much time
//                Pattern.compile("escjava[.]sortedProver[.]Lifter[.]FnTerm[.]dump[(].*"), // too much memory
//                Pattern.compile("escjava[.]sortedProver[.]Lifter[.]SortVar[.]toString[(].*"), // too much time
//                Pattern.compile("escjava[.]sortedProver[.]Lifter[.]warn[(].*"), // failed to write to prover
//                Pattern.compile("escjava[.]sortedProver[.]Lifter[.]convert[(].*"), // failed to write to prover
//                Pattern.compile("escjava[.]sortedProver[.]Lifter[.]newMethod[(].*"), // binary generic
//                Pattern.compile("escjava[.]sortedProver[.]Lifter[.]Lifter[(].*"), // failed to write to prover
//              Pattern.compile("javafe[.]ast[.][a-zA-Z]*[.]getTag[(].*"), // too much time
//                Pattern.compile("javafe[.]ast[.]CompoundName[.]prefix[(].*"), // out of resources
//                Pattern.compile("javafe[.]ast[.]BinaryExpr[.]getStartLoc[(].*"), // out of resources
//                Pattern.compile("javafe[.]ast[.]BinaryExpr[.]postCheck[(].*"), // out of resources
//                Pattern.compile("javafe[.]ast[.]BinaryExpr[.]accept[(].*"), // out of resources
//                Pattern.compile("javafe[.]Options[.]processOption[(].*"), // out of resources
//                Pattern.compile("javafe[.]parser[.]Token[.]ztoString[(].*"), // out of resources
//
//                Pattern.compile("javafe[.]ast[.].*[.]toString[(].*"), // out of resources
//                Pattern.compile("escjava[.]AnnotationHandler[.]NestedPragmaParser[.]parseAlsoSeq[(].*"), // out of resources
//                Pattern.compile("escjava[.]AnnotationHandler[.]NestedPragmaParser[.]parseSeq[(].*"), // out of resources
        
        };
        if (doPattern != null) {
            if (!doPattern.matcher(name).matches()) return;//{System.out.println("skipping " + name); return; }
        }
        for (Pattern avoid: avoids) {
            if (avoid.matcher(name).matches()) {System.out.println("skipping " + name); return; }
        }
        

        // FIXME - turn off in quiet mode? 
        //Log.printLines(log.noticeWriter,"["+(++ord)+"] "+ "ESC: Checking method "+ name);
        if (escdebug) System.out.println(node.toString()); // print the method

        boolean doTimingTests = false;
        
        if (!doTimingTests) {
            timingTest = 0;
            doMethodDef(node);
        } else {
            System.out.println("METHOD: " + name);
            //int[] timingTests = { 3,  1,2,3,4,5,6,7, 8, 1,2,3,4,5,6,7,8,1,2,3,4,5,6,7,8};
            //int[] timingTests = {1, 9,10,11,15,16,17,12,13,14,9,10,11,15,16,17,12,13,14,9,10,11,15,16,17,12,13,14 };
            //int[] timingTests = {1, 9,10,11,9,10,11,9,10,11 };
            int[] timingTests = { 1, 11,16, 11, 16, 11,16,11,16,11,16};
            // 0 = normal default running
            // 1 = with assert, no push, with defs, no evidence
            // 2 = with assert, no push, with defs, evidence  // vs. 1 gives cost of generating counterexamples
            // 3 = with assert, push, with defs, no evidence  // vs. 1 gives cost of assumption checking by push/pop and assumeChecks
            // 4 = with assert+, no push, with defs, no evidence // vs. 1 gives cost of assumption checking by retract 
            // 5 = with assert, push, with defs, evidence  // vs. 1 gives cost of assumption checking by push/pop and assumeChecks (when wanting evidence for CEs)
            // 6 = with assert+, no push, with defs, evidence // vs. 1 gives cost of assumption checking by retract (when wanting evidence for CEs)
            // 7 = with assert, no push, no defs, no evidence // vs. 1 gives cost of using definitions
            // 8 = with assert, no push, with defs, no evidence // vs. 1 gives cost of using tree form

            // Would like to measure the cost of using/not using definitions -- but some VCs get too big
            
            // UNSAT - all use assertion defs
            // 9 = like 10, but without truncating VC at a false assert
            // 10 = no evidence, with assert, no push, assume checks and defs, repeated checks with assert false
            // 11 = no evidence, with assert, push, assume checks and defs, repeated assume check with pop
            // 12 = no evidence, with assert+, no push, retract, assume checks and defs, repeated assume check with retract
            // 13 = evidence, with assert+, push, assume checks and defs, use core ids else repeated assume check with pop
            // 14 = evidence, with assert+, no push, retract, assume checks and defs, use core ids else repeated assume check with retract
            // 15 = like 11, but with evidence and with repeated checks for any CE
            // 16 = like 15, but with pushing and popping
            // 17 = like 15, but with retracting
            
            boolean ok = true;
            for (int ttest : timingTests) {
                timingTest = ttest;
                if (!ok && ttest >= 9) continue;
                YicesProver.assertPlus = true;
                JmlEsc.usePush = true;
                JmlEsc.useTree = false;
                BasicBlocker.insertAssumptionChecks = true;
                BasicBlocker.useAssertDefinitions = true;
                BasicBlocker.useAssumeDefinitions = true;
                
                if (timingTest > 0) {
                    YicesProver.assertPlus = timingTest == 4 || timingTest == 6 || timingTest == 12 || timingTest == 13 || timingTest == 14 || timingTest == 17;
                    YicesProver.evidence = timingTest == 2 || timingTest == 5 || timingTest == 6 || timingTest == 13 || timingTest == 14 || timingTest >= 15;
                    JmlEsc.usePush = timingTest == 3 || timingTest == 5 || timingTest == 11 || timingTest == 13 || timingTest == 15 || timingTest == 16;
                    JmlEsc.useRetract = timingTest == 12 || timingTest == 14 || timingTest == 17;
                    JmlEsc.useSearch = timingTest == 15 || timingTest == 16 || timingTest == 17;
                    JmlEsc.useCoreIds = timingTest == 13 || timingTest == 14;
                    BasicBlocker.insertAssumptionChecks = true;
                    BasicBlocker.useAssertDefinitions = timingTest != 7;
                    BasicBlocker.useAssumeDefinitions = timingTest != 7;
                    JmlEsc.useTree = timingTest == 8;
                }
                
                BasicBlocker.useCountedAssumeCheck = timingTest < 13;
                
                ok = doMethodDef(node);
            }
        }
    }
    
    public boolean doMethodDef(@NonNull JCMethodDecl node) {
        
        JmlMethodDecl tree = (JmlMethodDecl)node;
        // Get the denested specs for the method - FIXME - when might they be null?
        JmlMethodSpecs denestedSpecs = tree.sym == null ? null : specs.getDenestedSpecs(tree.sym);
        // Change the log's source file to represent the source for this method
        JavaFileObject source = tree.sourcefile;
        JavaFileObject prev = log.useSource(source);
        
        boolean ok = false;
            
        try {
            String name = node.sym.owner + "." + node.sym;
            
            boolean doMetrics = false;
            VCmode = 0;
            if (timingTest > 0) doMetrics = false;
            boolean showTimes = false;
            Utils.Timer t = null;
            if (showTimes) t = new Utils.Timer();
            if (doMetrics) {
                boolean a = BasicBlocker.useAssertDefinitions;
                boolean b = BasicBlocker.useAssumeDefinitions;
                try {
                BasicBlocker.useAssertDefinitions = false;
                BasicBlocker.useAssumeDefinitions = false;
                BasicProgram program = BasicBlocker.convertToBasicBlocks(context, tree, denestedSpecs, currentClassDecl);
                metrics(node,program,name);
                } finally {
                BasicBlocker.useAssertDefinitions = a;
                BasicBlocker.useAssumeDefinitions = b;
                }
            }
            if (useTree) VCmode = 1;
            BasicProgram program = BasicBlocker.convertToBasicBlocks(context, tree, denestedSpecs, currentClassDecl);
            if (doMetrics || timingTest == 1) metrics(node,program,name);
            if (doMetrics) return true;

            try {
                if (JmlOptionName.isOption(context,"-showbb") || escdebug) program.write(); // print the basic block program // FIXME - the option
                //if (showTimes) System.out.println("    ... prep           " +  t.elapsed()/1000.);
                //System.out.println("\t\t" + program.blocks().size() + " blocks, " + program.definitions().size() + " definitions, " + program.background().size() + " axioms, " + BasicBlocker.Counter.count(program) + " nodes");
                ok = prove(node,program);
                if (showTimes) System.out.println("    ... prep and prove " +  t.elapsed()/1000.);
                if (showTimes) {
                    Runtime rt = Runtime.getRuntime();
                    System.out.println("    ....... Memory free=" + rt.freeMemory() + "  max="+rt.maxMemory() + "  total="+rt.totalMemory());
                }
            } catch (RuntimeException e) {
                String se = e.toString();
                if (se.length() > 200) se = se.substring(0,200) + " .....";
                log.warning("esc.prover.failure",se);
                // go on with next 
            } catch (Throwable e) {
                String se = e.toString();
                if (se.length() > 200) se = se.substring(0,200) + " .....";
                log.warning("esc.prover.failure",se);
                System.gc();
            }
        } catch (RuntimeException e) {
            log.warning("esc.vc.prep",e);
            // go on with next 
        } catch (Throwable e) {
            log.warning("esc.vc.prep",e);
            System.gc();
        } finally {
            log.useSource(prev);
        }
        return ok;
    }
 
    
    /** Returns the VC expression for a basic block
     * 
     * @param block the block to convert
     * @return the equivalent expression
     */
    public @NonNull JCExpression blockExpr(@NonNull BasicBlock block) {
        java.util.List<JCStatement> statements = block.statements();
        Iterator<JCStatement> iterator = statements.iterator();
        return blockExpr(block,iterator);
    }
    
    public void metrics(JCMethodDecl node, BasicProgram program, String name) {
        VCmode = 0;
        int ast = BasicBlocker.Counter.countAST(node.body);
        int sts = BasicBlocker.Counter.countASTStatements(node.body);
        BasicBlocker.Counter c = BasicBlocker.Counter.count(program);
//        VCmode = 1;
//        JCTree f = blockExpr(program.startBlock());
//        int fan = BasicBlocker.Counter.countAST(f) + BasicBlocker.Counter.countx(program);

//        BasicProgram newbp = new BasicProgram();
//        newbp.definitions = program.definitions;
//        newbp.background = program.background;
//        java.util.List<JCStatement> list = new java.util.ArrayList<JCStatement>();
//        newblocks(list,program.startBlock(),program,newbp);
//        int lin = BasicBlocker.Counter.countx(newbp);
//        for (BasicBlock b: newbp.blocks) {
//            lin += BasicBlocker.Counter.countAST(blockExpr(b));
//        }
//        System.out.println(ast + " AST; " + c + "  " + fan + " tree; " + lin + " linear; " + program.definitions.size() + " defs :: " + name);
        VCmode = 0;
        
        int oth =  Counter.countx(program);
        int fan1 = fanCount(program).nodes + oth;
        int lin1 = parCount(program,false).nodes + oth;
        int linf = parCount(program,true).nodes + oth;
        System.out.println(ast + " AST; " + sts + " statements; " + c + "  " + fan1 + " tree; " + lin1 + " linear; " + linf + " fulllinear; " + program.definitions.size() + " defs :: " + name);

    }
    
    Map<BasicBlock,Counter> countCache = new HashMap<BasicBlock,Counter>();
    
    public Counter parCount(BasicProgram program, boolean full) {
        countCache.clear();
        Counter c = getParCount(program.startBlock(),program,full);
        c.nodes += c.paths;
        return c;
    }
    
    public Counter getParCount(BasicBlock block, BasicProgram program, boolean full) {
        Counter c = countCache.get(block);
        if (c == null) {
            c = parCount(block,program,full);
            //System.out.println("CACHE " + block.id + " " + c.paths + " " + c.nodes);
            countCache.put(block,c);
        }
        return c;
    }
    
    public Counter parCount(BasicBlock block, BasicProgram program, boolean full) {
        Counter c = new Counter();
        Counter ca = new Counter();
        int n = 0;
        for (JCTree t: block.statements()) {
            t.accept(c);
            //c.nodes++;
            if (full && t instanceof JmlStatementExpr && ((JmlStatementExpr)t).token == JmlToken.ASSERT) {
                ca.add(c);
                n++;
            }
        }
        
//        System.out.print(block.id + " " + c.nodes + " ");
//        for (BasicBlock b: block.succeeding) System.out.print(" " + b.id);
//        System.out.println();
//        
        if (block.succeeding.size() == 0) {
            ca.add(c);
            n++;
            ca.paths = n;
            return ca;
        }
        Counter cc = new Counter();
        for (BasicBlock b: block.succeeding) {
            Counter ccc = getParCount(b,program,full);
            for (int i=0; i<ccc.paths; i++) {
                cc.add(c);
            }
            cc.add(ccc);
            n += ccc.paths;
            //cc.nodes ++;
        }
        cc.add(ca);
        cc.paths = n;
        return cc;
    }
    
    public Counter fanCount(BasicProgram program) {
        countCache.clear();
        return fanCount(program.startBlock(),program);
    }
    
    public Counter getFanCount(BasicBlock block, BasicProgram program) {
        Counter c = countCache.get(block);
        if (c == null) {
            c = fanCount(block,program);
            countCache.put(block,c);
        }
        return c;
    }
    
    public Counter fanCount(BasicBlock block, BasicProgram program) {
        Counter c = new Counter();
        c.count(block);
        if (block.succeeding.size() == 0) {
            //c.nodes++;
            return c;
        }
        for (BasicBlock b: block.succeeding) {
            c.add(getFanCount(b,program));
            //c.nodes ++;
        }
        return c;
    }
    
    public void newblocks(java.util.List<JCStatement> prefix, BasicBlock block, BasicProgram program, BasicProgram newp) {
        //System.out.println("NEWBLOCKS " + block.id + "   prefix = " + Counter.counts(prefix));
        java.util.List<JCStatement> p = new java.util.ArrayList<JCStatement>();
        p.addAll(prefix);
        for (JCStatement s: block.statements) {
            p.add(s);
            if ((s instanceof JmlTree.JmlStatementExpr) && ((JmlTree.JmlStatementExpr)s).token == JmlToken.ASSERT) {
                BasicBlock bb = new BasicBlock(null);
                bb.statements.addAll(p);
                newp.blocks.add(bb);
                //System.out.println(    "  BLOCK-A " + Counter.counts(bb));
            }
        }
        if (block.succeeding.size() == 0) {
            BasicBlock bb = new BasicBlock(null);
            bb.statements.addAll(p);
            newp.blocks.add(bb);
            //System.out.println(    "  BLOCK-B " + Counter.counts(bb));
        } else {
            for (BasicBlock bb: block.succeeding) {
                newblocks(p,bb,program,newp);
            }
        }
    }
    
    /** Helper method to determine the VC expression for a basic block.
     * 
     * @param block BasicBlock being translated
     * @param iterator an iterator over the statements of the block
     * @return the equivalent VC expression
     */
    protected @NonNull JCExpression blockExpr(@NonNull BasicBlock block, @NonNull Iterator<JCStatement> iterator) {
        if (iterator.hasNext()) {
            JCStatement st = iterator.next();
            JCExpression rest = null;
            if (st == targetStatement) {
                if (timingTest == 9) {
                    rest = blockExpr(block,iterator);
                    JCExpression e = factory.Literal(TypeTags.BOOLEAN,0); // FALSE
                    e.type = syms.booleanType;
                    e.pos = st.pos;
                    e = factory.Binary(JCTree.AND,e,rest);
                    e.type = syms.booleanType;
                    e.pos = st.pos;
                    return e;
                } else {
                    JCExpression e = factory.Literal(TypeTags.BOOLEAN,0); // FALSE
                    e.type = syms.booleanType;
                    e.pos = st.pos;
                    return e;
                }
            } else if (st instanceof JmlStatementExpr) {
                rest = blockExpr(block,iterator);
                JmlStatementExpr as = (JmlStatementExpr)st;
                if (as.token == JmlToken.ASSUME) {
                    JCExpression e = factory.JmlBinary(JmlToken.IMPLIES,as.expression,rest);
                    e.type = syms.booleanType;
                    e.pos = as.expression.pos;
                    return e;
                } else if (as.token == JmlToken.ASSERT) {
                    //JCExpression e = factory.JmlBinary(JmlToken.IMPLIES,as.expression,rest);
                    JCExpression e = factory.Binary(JCTree.AND,as.expression,rest);
                    e.type = syms.booleanType;
                    e.pos = as.expression.pos;
                    return e;
                } else {
                    log.error("esc.internal.error","An unexpected statement type in a BasicBlock: " + as.token.internedName());
                }
            } else {
                log.error("esc.internal.error","An unexpected statement type in a BasicBlock: " + st.getClass());
            }
            return rest;
        } else {
            JCExpression expr = factory.Literal(TypeTags.BOOLEAN,1); // TRUE
            expr.type = syms.booleanType;
            if (VCmode == 0) {
                for (BasicBlock follower: block.succeeding()) {
                    JCExpression e = factory.Binary(JCTree.AND,expr,follower.id);
                    e.pos = follower.id.pos;
                    e.type = syms.booleanType;
                    expr = e;
                }
            } else if (VCmode == 1) {
                for (BasicBlock follower: block.succeeding()) {
                    JCExpression fexpr = blockExpr(follower);
                    JCExpression e = factory.Binary(JCTree.AND,expr,fexpr);
                    e.pos = follower.id.pos;
                    e.type = syms.booleanType;
                    expr = e;
                }
            }
            return expr;
        }
    }
    
    int VCmode = 0;  // 0 - basic blocks; 1 - tree; 2 - parallel

    /** Creates an AST node for a new identifier, meant as an auxiliary logical
     * variable in the eventual VC; the identifier has the given type and node
     * position (the given position is not encoded into the identifier name);
     * an associated VarSymbol is also created.
     * @param name the name of the identifier (including any encoded numbers)
     * @param type the Java type of the identifier (e.g. syms.booleanType)
     * @param nodepos the pseudo source position at which to place the node
     * @return the created identifier
     */
    protected @NonNull JCIdent newAuxIdent(@NonNull Name name, @NonNull Type type, int nodepos) {
        JCIdent id = factory.Ident(name);
        id.pos = nodepos;
        id.type = type;
        id.sym = new VarSymbol(0,name,type,null);
        // Note: the owner of the symbol is null, because we do not want it to
        // be interpreted as a Java declaration of some sort.
        return id;
    }
    
    BasicBlock containingBlock;
    JCStatement targetStatement;
    
    /** Initiate proving of the VC for the method.  The given program must be
     * the BasicProgram corresponding to the given method declaration.
     * @param methodDecl the method whose implementation is being proved
     * @param program the basic program corresponding to the method implementation
     */
    public boolean prove(@NonNull JCMethodDecl methodDecl, @NonNull BasicProgram program) {
        String name = methodDecl.sym.owner + "." + methodDecl.sym;
        boolean ok = false;
        IProver p = null;
        try {

            // Pick a prover to use
            String proverToUse = "yices";
            //String proverToUse = "cvc";
            //String proverToUse = "simplify";
            p = AbstractProver.getProver(context,proverToUse);
            if (useRetract && !p.supports().retract) { p.kill(); return true; }
            if (useCoreIds && !p.supports().unsatcore) { p.kill(); return true; }
            if (timingTest >= 15 && p instanceof CVC3Prover) { p.kill(); return true;}
            
            boolean showTimes = timingTest > 0;
            Utils.Timer timer = null;
            if (showTimes) timer = new Utils.Timer();

//            for (BasicProgram.BasicBlock block : program.blocks()) {
//                p.define(block.id.toString(),syms.booleanType);
//            }
            
//            if (JmlOptionName.isOption(context,"-checkPreconditions")) {
//                // Check that the preconditions are satisfiable
//                // This is here in case one wants a quick smoke test of the 
//                // preconditions.  Normally one would do the general check of
//                // the method, and only if it is successful would one go on to
//                // check that the various assumptions are feasible.
//                p.push();
//                BasicBlock bl = program.startBlock();
//                JCExpression e = blockExpr(bl);
//                e = factory.Unary(JCTree.NOT,e);
//                e.type = syms.booleanType;
//                p.assume(e);
//                IProverResult b = p.check(false);
//                if (b.result() == ProverResult.UNSAT) {
//                    log.warning(methodDecl.pos(),"esc.unsat.preconditions",methodDecl.getName());
//                    if (escdebug) System.out.println("Invariants+Preconditions are NOT satisfiable in " + methodDecl.getName());
//                    // FIXME - print out core ids if available?
//                    p.pop();
//                    return false;
//                } else {
//                    if (verbose) System.out.println("Invariants+Preconditions are satisfiable");
//                }
//                p.pop();
//            }
            
            // send negated start block id
            
            JCExpression negateStart = null;
            if (!(p instanceof SimplifyProver)) {
                JCIdent id = program.startBlock().id();
                negateStart = factory.Unary(JCTree.NOT,id);
                negateStart.type = syms.booleanType;
                p.assume(negateStart);
            }

            // send block equations

            containingBlock = null;
            for (BasicBlock bl : program.blocks()) {
                JCExpression e = blockExpr(bl);
                e = factory.JmlBinary(JmlToken.EQUIVALENCE,bl.id,e); 
                e.pos = bl.id.pos;
                e.type = syms.booleanType;
                //System.out.println("BLOCK " + bl.id + " " + Counter.countAST(e));
                p.assume(e);
            }

            // send any other axioms and definitions
            
            int assertionNumber = 0;
            for (JCExpression expr: program.background()) {
                assertionNumber = p.assume(expr);
            }
            
            Map<JCExpression,Integer> defnum = new HashMap<JCExpression,Integer>();
            for (JCExpression expr: program.definitions()) {
                assertionNumber = p.assume(expr);
                defnum.put(expr,assertionNumber);
            }

            if (checkAssumptions && usePush) p.push();

            int assumptionCheck =0;
            if (BasicBlocker.insertAssumptionChecks) { // We have to include this unless no assumption encoding is done
                if (BasicBlocker.useCountedAssumeCheck) {
                    JCExpression e = factory.Literal(TypeTags.INT,0);
                    e.type = syms.intType;
                    e = factory.Binary(JCTree.EQ, program.assumeCheckVar, e);
                    e.type = syms.booleanType;
                    assumptionCheck = p.assume(e);
                } else {
                    assumptionCheck = p.assume(BasicBlocker.booleanAssumeCheck);
                }
            }

            long time2=0,time3=0;

            IProverResult r = p.check(YicesProver.evidence);
            if (showTimes) {
                time2 = timer.elapsed();
                timer.reset();
            }
            
            ok = !r.isSat();
            if (timingTest > 0 && timingTest < 9) {
                if (showTimes) System.out.println("TIMES-" + timingTest + " " + time2 + " " + (r.isSat()?"SAT":"UNSAT") + " :: " + name);
                return ok;
            }
            
            Utils.Timer timer2 = new Utils.Timer();
            Utils.Timer timer3 = new Utils.Timer();
            if (r.isSat()) {
                if (showTimes) System.out.println("TIMES-" + timingTest + " " + time2 + " SAT :: " + name);
                if (escdebug) System.out.println("Method does NOT satisfy its specifications, it appears");
                if (timingTest == 0) displayCounterexampleInfo(methodDecl, program, p, r);
                p.kill();
            } else if (r.result() == IProverResult.UNSAT && (timingTest == 10 || timingTest==9)) {
                if (escdebug) System.out.println("Method satisfies its specifications (as far as I can tell)");
                p.kill();
                if (!checkAssumptions) return ok;
                
                int numbad = 0;
                for (BasicBlock blk : program.blocks()) {
                    for (JCStatement stt: blk.statements) {
                        if (stt instanceof JmlStatementExpr && ((JmlStatementExpr)stt).label == Label.ASSUME_CHECK) {
                            timer3.reset();
                            targetStatement = stt;
                            containingBlock = blk;

                            JCIdent ek = (JCIdent)((JmlStatementExpr)stt).expression;

                            String eid = ek.name.toString();
                            int pp = eid.lastIndexOf('$');
                            int ps = eid.indexOf('$');
                            int pos = Integer.parseInt(eid.substring(ps+1,pp));
                            String label = eid.substring(pp+1);

                            
                            // Find the block containing the expression to check
                            //                    containingBlock = null;
                            //                    targetStatement = null;
                            //                    outer: for (BasicBlock bl : program.blocks()) {
                            //                        for (JCStatement st: bl.statements) {
                            //                            if (st instanceof JmlStatementExpr && ((JmlStatementExpr)st).expression == ek) {
                            //                                targetStatement = st;
                            //                                containingBlock = bl;
                            //                                break outer;
                            //                            }
                            //                        }
                            //                    }
                            //                    if (containingBlock == null) {
                            //                        System.out.println("COULD NOT FIND ASSUMPTION");
                            //                        continue;
                            //                    }

                            Set<BasicBlock> neededBlocks = new HashSet<BasicBlock>();
                            List<BasicBlock> todo = new LinkedList<BasicBlock>();
                            todo.add(containingBlock);
                            while (!todo.isEmpty()) {
                                BasicBlock b = todo.remove(0);
                                if (neededBlocks.contains(b)) continue;
                                todo.addAll(b.preceding);
                                neededBlocks.add(b);
                            }
                            
                            p = AbstractProver.getProver(context,proverToUse);
                            
//                            for (BasicProgram.BasicBlock block : program.blocks()) {
//                                p.define(block.id.toString(),syms.booleanType);
//                            }

                            // send negated start block id

                            if (!(p instanceof SimplifyProver)) p.assume(negateStart);

                            // send block equations

                            for (BasicBlock bl : program.blocks()) {
                                JCExpression e = factory.Literal(TypeTags.BOOLEAN,1);  // TRUE
                                if (timingTest == 9 || neededBlocks.contains(bl)) {
                                    e = blockExpr(bl);
                                }
                                e = factory.JmlBinary(JmlToken.EQUIVALENCE,bl.id,e); 
                                e.pos = bl.id.pos;
                                e.type = syms.booleanType;
                                //System.out.println("BLOCK " + bl.id + " " + Counter.countAST(e));
                                p.assume(e);
                            }

                            // send any other axioms and definitions

                            assertionNumber = 0;
                            for (JCExpression expr: program.background()) {
                                assertionNumber = p.assume(expr);
                            }

                            defnum = new HashMap<JCExpression,Integer>();
                            for (JCExpression expr: program.definitions()) {
                                assertionNumber = p.assume(expr);
                                defnum.put(expr,assertionNumber);
                            }

                            if (BasicBlocker.insertAssumptionChecks) { // We have to include this unless no assumption encoding is done
                                JCExpression e = factory.Literal(TypeTags.INT,0);
                                e.type = syms.intType;
                                e = factory.Binary(JCTree.EQ, program.assumeCheckVar, e);
                                e.type = syms.booleanType;
                                assumptionCheck = p.assume(e);
                            }

                            r = p.check(false);
                            if (!r.isSat() && timingTest == 0) {
                                reportAssumptionProblem(label,pos,methodDecl.sym.toString());
                            }
                            if (!r.isSat()) {
                                numbad++;
                            }
                            p.kill();
                            long t3 = timer3.elapsed();
                            //System.out.println("CHECKING " + eid + " " + r.isSat() +  " " + t3);

                        }
                    }
                }

                if (showTimes) {
                    time3 = timer.elapsed();
                    System.out.println("TIMES-" + timingTest + " " + time2 + " " + time3 + " UNSAT checks: " + program.assumptionsToCheck.size() + " " + numbad + " " + (-1) + " :: " + name);
                }

            } else if (r.result() == IProverResult.UNSAT && timingTest == 15 && !(p instanceof CVC3Prover)) {
                if (escdebug) System.out.println("Method satisfies its specifications (as far as I can tell)");
                if (!checkAssumptions) return ok;
                int numcore = -1;
                int numbad = 0;
                if (usePush) { p.pop();  }
                String pcname = program.assumeCheckVar.sym.toString();
                int num = program.assumptionsToCheck.size();
                List<Integer> list = new ArrayList<Integer>(num);
                JCExpression exx = factory.Literal(TypeTags.BOOLEAN,1); // TRUE
                exx.type = syms.booleanType;
                while (num > 0) {
                    timer2.reset();
                    if (p instanceof CVC3Prover) {
                        p.push();
                        p.assume(exx);
                    }
                    r = p.check(true);
                    if (!r.isSat()) {
//                        System.out.println("CHECKING " + "UNSAT" + " " + r.isSat() + " " + timer2.elapsed());
//                        System.out.println("EVERYTHING ELSE IS INFEASIBLE " + num);
                        numbad = num;
                        break;
                    }
                    String result = r.counterexample().get(pcname);
                    if (result == null) {
//                        System.out.println("NO RESULT");
                        break;
                    }
                    if (p instanceof CVC3Prover) p.pop();
//                    System.out.println("RESULT IS " + result);
                    int pos = Integer.parseInt(result);
                    JCExpression ex = factory.Binary(JCTree.NE, program.assumeCheckVar, factory.Literal(TypeTags.INT,pos));
                    ex.type = syms.booleanType;
                    exx = factory.Binary(JCTree.AND,exx,ex);
                    exx.type = syms.booleanType;
                    if (!(p instanceof CVC3Prover)) p.assume(ex);
                    list.add(pos);
                    --num;
//                    long t2 = timer2.elapsed();
//                    System.out.println("CHECKING " + result + " " + r.isSat() + " " + t2);
                }
                if (showTimes) {
                    time3 = timer.elapsed();
                    System.out.println("TIMES-" + timingTest + " " + time2 + " " + time3 + " UNSAT checks: " + program.assumptionsToCheck.size() + " " + numbad + " " + (-1) + " :: " + name);
                }
                p.kill();
                for (Map.Entry<JCExpression,String> nme:   program.assumptionsToCheck) {
                    String nm = nme.getValue();
                    int k = nm.indexOf('$');
                    int kk = nm.indexOf('$',k+1);
                    int ps = Integer.parseInt(nm.substring(k+1,kk));
                    if (list.contains(ps)) continue;
                    if (timingTest == 0) reportAssumptionProblem(nm.substring(kk+1),ps,methodDecl.sym.toString());
                }
                
            } else if (r.result() == IProverResult.UNSAT) {  // 11, 12, 13, 14, 16, 17
                if (escdebug) System.out.println("Method satisfies its specifications (as far as I can tell)");
                if (!checkAssumptions) return ok;
                
                //boolean useCoreIds = true; // FIXME - use an option
                //if (timingTest > 0) useCoreIds = timingTest == 11;

                ICoreIds cid = r.coreIds();
                if (useCoreIds && cid == null && verbose) System.out.println("Warning: Core ids unexpectedly not returned");
                Collection<Integer> cids = cid == null ? null : cid.coreIds();
                Integer[] ids = new Integer[0];
                if (useCoreIds && cids != null) {
                  ids = new Integer[cids.size()]; 
                  ids = cids.toArray(ids);
                }
                Arrays.sort(ids);
                int numcore = 0;
                int numbad = 0;
                if (useSearch) numbad = program.assumptionsToCheck.size();
                if (usePush) p.pop();
                String pcname = program.assumeCheckVar.sym.toString();
                JCExpression exx = factory.Literal(TypeTags.BOOLEAN,1);
                exx.type = syms.booleanType;
                for (Map.Entry<JCExpression,String> nm: program.assumptionsToCheck) {
                    timer2.reset();
                    JCExpression e = nm.getKey();
                    String eid = nm.getValue();
                    int pp = eid.lastIndexOf('$');
                    int ps = eid.indexOf('$');
                    int pos = Integer.parseInt(eid.substring(ps+1,pp));
                    String label = eid.substring(pp+1);
                    if (useCoreIds) {
                        int k = defnum.get(e);
                        int found = Arrays.binarySearch(ids,k);
                        if (found < 0) {
                            // Already not part of the minimal core
                            numcore++;
                            numbad++;
                            //                            if (escdebug || timingTest > 0) System.out.println("ALREADY NOT IN MINIMAL CORE: " + pos + " " + label);
                            if (timingTest == 0) reportAssumptionProblem(label,pos,methodDecl.sym.toString());
                            continue;
                        }
                    }
                    if (useSearch) {
                        if (useRetract) {
                            p.retract(assumptionCheck);
                            assumptionCheck = ((YicesProver)p).assumePlus(exx);
                            r = p.check(true);
                        } else if (usePush) {
                            p.push();
                            p.assume(exx);
                            r = p.check(true);
                            p.pop();
                        }
                        if (!r.isSat()) {
                            //                              System.out.println("CHECKING " + "UNSAT" + " " + r.isSat() + " " + timer2.elapsed());
                            //                              System.out.println("EVERYTHING ELSE IS INFEASIBLE " + num);
//                            long t2 = timer2.elapsed();
//                            System.out.println("CHECKING " + r.result() + " " + t2 + " " + eid);
                            break;
                        }
                        String result;
                        if (!BasicBlocker.useCountedAssumeCheck) {
                            JCExpression rres = null;
                            for (Map.Entry<JCExpression,String> nmm: program.assumptionsToCheck) {
                                String res = r.counterexample().get(nmm.getValue());
                                if (res == null || res.equals("true")) continue;
                                //System.out.println(nmm.getValue() + " IS FALSE " + res);
                                if (hasFeasibleChain(findContainingBlock(nmm.getKey(),program),r.counterexample())) {
                                    //System.out.println(nmm.getValue() + " IS FEASIBLE");
                                    rres = nmm.getKey();
                                    break;
                                }
                            }
                            if (rres == null) {
                                System.out.println("NO RESULT");
                                break;
                            }
                            exx = factory.Binary(JCTree.AND,rres,exx);
                            exx.type = syms.booleanType;
                        } else {
                            result = r.counterexample().get(pcname);
                            if (result == null) {
                                //                              System.out.println("NO RESULT");
                                break;
                            }
                            int pps = Integer.parseInt(result);
                            JCExpression ex = factory.Binary(JCTree.NE, program.assumeCheckVar, factory.Literal(TypeTags.INT,pps));
                            ex.type = syms.booleanType;
                            exx = factory.Binary(JCTree.AND,ex,exx);
                            exx.type = syms.booleanType;
                        }

                        //                      if (escdebug || timingTest > 0) {
                        //                      if (r.isSat()) {
                        //                          System.out.println("NOW SAT - ASSUMPTION WAS OK: " + pos + " " + label);
                        //                      } else {
                        //                          if (useCoreIds) System.out.println("STILL UNSAT - CORE WAS NOT MINIMAL - ASSUMPTION WAS INFEASIBLE: " + pos + " " + label);
                        //                          else System.out.println("STILL UNSAT - ASSUMPTION WAS INFEASIBLE: " + pos + " " + label);
                        //                      }
                        //                      }
                        //                        if (!r.isSat() && timingTest == 0) {
                        //                            reportAssumptionProblem(label,pos,methodDecl.sym.toString());
                        //                        }
                        numbad--;
//                        long t2 = timer2.elapsed();
//                        System.out.println("CHECKING " + r.result() + " " + t2 + " " + pps);
                    } else {
                        JCExpression ex = factory.Binary(JCTree.EQ, program.assumeCheckVar, factory.Literal(TypeTags.INT,pos));
                        ex.type = syms.booleanType;
                        if (useRetract) {
                            p.retract(assumptionCheck);
                            assumptionCheck = ((YicesProver)p).assumePlus(ex);
                            r = p.check(false);
                        } else if (usePush) {
                            p.push();
                            p.assume(ex);
                            r = p.check(false);
                            p.pop();
                        }
                        //                      if (escdebug || timingTest > 0) {
//                                              if (r.isSat()) {
//                                                  System.out.println("NOW SAT - ASSUMPTION WAS OK: " + pos + " " + label);
//                                              } else {
//                                                  if (useCoreIds) System.out.println("STILL UNSAT - CORE WAS NOT MINIMAL - ASSUMPTION WAS INFEASIBLE: " + pos + " " + label);
//                                                  else System.out.println("STILL UNSAT - ASSUMPTION WAS INFEASIBLE: " + pos + " " + label);
//                                              }
                        //                  }
                        if (!r.isSat() && timingTest == 0) {
                            reportAssumptionProblem(label,pos,methodDecl.sym.toString());
                        }
                        if (!r.isSat()) {
                            numbad++;
                        }
//                        long t2 = timer2.elapsed();
//                        System.out.println("CHECKING " + r.result() + " " + t2 + " " + eid);
                    }
                }
                if (showTimes) {
                    time3 = timer.elapsed();
                    System.out.println("TIMES-" + timingTest + " " + time2 + " " + time3 + " UNSAT checks: " + program.assumptionsToCheck.size() + " " + numbad + " " + (useCoreIds?numcore:-1) + " :: " + name);
                }
            } else {
                // Result is unknown
                // FIXME - need some tests and more output information here
                if (escdebug) System.out.println("Status of method is UNKNOWN - prover failed");
                log.error("esc.proof.failed", r.result(), methodDecl.sym);
            }

        } catch (ProverException e) {
            String se = e.mostRecentInput == null ? "" :e.mostRecentInput;
            if (se.length() > 200) se = se.substring(0,200) + " .......";
            log.warning("esc.prover.failure",methodDecl.sym.toString() + ": " + e.getLocalizedMessage() + ":" + se);
            if (escdebug) {
                System.out.println("PROVER FAILURE: " + e);
                if (e.mostRecentInput != null) System.out.println("INPUT: " + se);
                e.printStackTrace(System.out);
            }
            try {
                if (p != null) p.kill();
            } catch (ProverException ee) {
                log.warning("esc.internal.error","Failed to kill process: " + ee);
                // Report but ignore any problems in killing
            }
        } catch (Throwable e) {
            log.warning("esc.prover.failure",methodDecl.sym.toString() + ": " + e.getLocalizedMessage());
            if (escdebug) System.out.println("PROVER FAILURE: " + e.getClass() + " " + e);
            e.printStackTrace(System.out);
        }
        return ok;
    }


    /** For a SAT result, this prints out counterexample information in a human
     * usable form
     * @param methodDecl The declaration of the method being verified
     * @param program The Basic program for that method
     * @param p The prover being used
     * @param r The result from that prover
     */
    protected void displayCounterexampleInfo(JCMethodDecl methodDecl,
            BasicProgram program, IProver p, IProverResult r) {
        ICounterexample s = r.counterexample();
        boolean noinfo = true;
        if (s != null) {
            // Find out the termination position; null means that the information
            // was not available from the counterexample - either because the
            // prover did not return it, or because of some bug in the
            // program
            String terminationValue = s.get(BasicBlocker.TERMINATION_VAR);
            int terminationPosition = terminationValue == null ? 0 :
                                Integer.valueOf(terminationValue);
            // Find the assert with the smallest uniqueness number, that is in a feasible block
            //    FIXME - we're presuming that the one with the smallest uniqueness number comes
            //     earliest in the block; subsequent assertions may also be reportable, but if
            //     they come after assumptions, they are suspect
            // Look for "assert$<number>$<number>(@<number>)?$<Label>$<number>"
            Pattern pat1 = Pattern.compile("\\Aassert\\$(\\d+)\\$(\\d+)(@(\\d+))?\\$(\\w+)\\$(\\d+)\\z");
            Matcher found = null;
            int foundNum = Integer.MAX_VALUE;
            for (Map.Entry<String,String> var: s.sortedEntries()) {
                String sname = var.getKey();
                if (!sname.startsWith("assert$")) continue;
                Matcher m = pat1.matcher(sname);
                if (var.getValue().equals("false") && m.find()) {
                    Name v = names.fromString(sname);
                    BasicBlock bl = findContainingBlock(v,program);
                    if (bl == null || hasFeasibleChain(bl,s) ) {
                        int nn = Integer.parseInt(m.group(6));
                        if (nn < foundNum) { foundNum = nn; found = m; }
                        if (escdebug) System.out.println("Assertion " + sname + " is false");
                    }
                }
            }
            if (found != null) {
                Matcher m = found;
                    String sname = m.group(0); // full name of the assertion
                    String label = m.group(5); // the label part 
                    int usepos = Integer.parseInt(m.group(1)); // the textual location of the assert statement
                    int declpos = Integer.parseInt(m.group(2)); // the textual location of associated information (or same as usepos if no associated information)
                    JavaFileObject jfo = null;
                    String fintstr = m.group(4);
                    if (fintstr != null) {
                        Integer i = Integer.valueOf(fintstr);
                        jfo = BasicBlocker.jfoArray.get(i);
                    }
                    int termpos = usepos;
                    if (terminationValue != null &&
                            (Label.POSTCONDITION.toString().equals(label) ||
                                    Label.INVARIANT.toString().equals(label) ||
                                    Label.CONSTRAINT.toString().equals(label) ||
                                    Label.INITIALLY.toString().equals(label) ||
                                    Label.SIGNALS.toString().equals(label) ||
                                    Label.SIGNALS_ONLY.toString().equals(label))) {
                        // terminationPosition is, 
                        // if positive, the source code location of the return statement,
                        // if negative, the negative of the source code location of
                        //          the throw statement or method call of an exception exit
                        // if 0, the method exits out the end of the block.  
                        // In this last case, one would like to point the
                        // error message to the end of the block, but since
                        // we do not at the moment have support for 
                        // end positions, we use the position of the 
                        // method declaration. (TODO)
                        if (terminationPosition == 0) termpos = usepos; 
                        else if (terminationPosition > 0) termpos = terminationPosition;
                        else                             termpos = -terminationPosition;
                    }
                    Name v = names.fromString(sname);
                    BasicBlock bl = findContainingBlock(v,program);
                    // A 'false' assertion is spurious if it happens in a block 
                    // which is not executed (its block variable is 'true')
                    // So we list the assertion if
                    //      - we cannot find a block containing the assertion (just to be safe)
                    //      - we find a block but find no value for the block variable (just to be safe)
                    //      - the block variable is 'false' (not 'true') and there is a chain of false blocks back to the beginning
                    if (bl == null || hasFeasibleChain(bl,s) ) {
                        log.warning(termpos,"esc.assertion.invalid",label,methodDecl.getName());
                        if (declpos != termpos || jfo != null) {
                            JavaFileObject prev = log.currentSource();
                            if (jfo != null) log.useSource(jfo);
                            log.warning(declpos,"esc.associated.decl");
                            log.useSource(prev);
                        }
                        //if (declpos != usepos) Log.printLines(log.noticeWriter,"Associated information");
                        noinfo = false;
                    }
                }
            
        }

        if (noinfo) {
            log.warning("esc.method.invalid",methodDecl.getName());
        } else {
            Pattern pat2 = Pattern.compile("\\$\\$LBLPOS\\$(\\d+)\\$([^ ]+)");
            for (Map.Entry<String,String> var: s.sortedEntries()) {
                Matcher m = pat2.matcher(var.getKey());
                if (var.getValue().equals("true") && m.find()) {
                    int pos = Integer.parseInt(m.group(1));
                    String label = m.group(2);
                    log.warning(pos,"esc.label",label);
                    if (escdebug) System.out.println("Label " + label + " has value " + var.getValue());
                }
            }
            Pattern pat3 = Pattern.compile("\\$\\$LBLNEG\\$(\\d+)\\$([^ ]+)");
            for (Map.Entry<String,String> var: s.sortedEntries()) {
                Matcher m = pat3.matcher(var.getKey());
                if (var.getValue().equals("false") && m.find()) {
                    int pos = Integer.parseInt(m.group(1));
                    String label = m.group(2);
                    log.warning(pos,"esc.label",label);
                    if (escdebug) System.out.println("Label " + label + " has value " + var.getValue());
                }
            }
            Pattern pat4 = Pattern.compile("\\$\\$LBLANY\\$(\\d+)\\$([^ ]+)");
            for (Map.Entry<String,String> var: s.sortedEntries()) {
                Matcher m = pat4.matcher(var.getKey());
                if (m.find()) {
                    int pos = Integer.parseInt(m.group(1));
                    String label = m.group(2);
                    log.warning(pos,"esc.label.value",label,var.getValue());
                    if (escdebug) System.out.println("Label " + label + " has value " + var.getValue());
                }
            }
        }
        
        if (showTrace || escdebug) {
            System.out.println("Trace " + methodDecl.getName());
            //BasicBlocker.Tracer.trace(context,methodDecl,s);
            BasicBlocker.TracerBB.trace(context,program,s,p);
        }
        if (showCounterexample || escdebug) {
            System.out.println("Counterexample:");
            // Just some arbitrary number of spaces used to format lines
            String spaces = "                                ";
            for (Map.Entry<String,String> var: s.sortedEntries()) {
                int k = var.getKey().length();
                if (k >= spaces.length()) k = spaces.length()-1;
                System.out.println("    " + var.getKey() + spaces.substring(k) + var.getValue());
            }
        }
    }
    
    /** Reports the details of a problem when an assumption is infeasible.
     * This happens when the assumption is not needed, which is known when
     * the program is still valid (the VC is unsatisfiable) with a false
     * assertion following the assumption.
     * 
     * @param label the label given to the assumption
     * @param pos   the textual position of the associated statement
     * @param method the name of the method being tested
     */
    public void reportAssumptionProblem(String label, int pos, String methodSignature) {
        if (label.equals(Label.BRANCHT.toString())) {
            log.warning(Math.abs(pos),"esc.infeasible.branch","then",methodSignature);
            if (escdebug) System.out.println("Branch is infeasible at " + pos);
        } else if (label.equals(Label.BRANCHE.toString())) {
            log.warning(Math.abs(pos),"esc.infeasible.branch","else",methodSignature);
            if (escdebug) System.out.println("Branch is infeasible at " + pos);
        } else if (label.equals(Label.CASECONDITION.toString())) {
            log.warning(Math.abs(pos),"esc.infeasible.case",methodSignature);
            if (escdebug) System.out.println("Switch case is infeasible at " + pos);
        } else if (label.equals(Label.PRECONDITION.toString())) {
            log.warning(Math.abs(pos),"esc.infeasible.preconditions",methodSignature);
            if (escdebug) System.out.println("Preconditions are infeasible at " + pos);
        } else {
            log.warning(pos,"esc.infeasible.assumption",methodSignature);
            if (escdebug) System.out.println("Assumption (" + label + ") is infeasible");
        }
    }
    
    /** Checks to see if the given BasicBLock has a feasible chain back to the
     * program start, within the set of variable assignments given in a
     * counterexample.  A BasicBlock is feasible if its block variable is 'false' in
     * the counterexample and either it is the start block or it follows a
     * feasible block.
     * @param bl the BasicBlock to check
     * @param s the counterexample whose assignments to use
     * @return true if feasible, false if not
     */
    public boolean hasFeasibleChain(/*@ non_null*/ BasicBlock bl, /*@ non_null*/ ICounterexample s) {
        if ("true".equals(s.get(bl.id.name.toString()))) return false;
        if (bl.preceding.size() == 0) return true; // presuming it is the start block, which may not be the case?? FIXME
        for (BasicBlock b: bl.preceding) {
            if (hasFeasibleChain(b,s)) return true;
        }
        return false;
    }
    
    /** Finds the basic block containing an assertion with the given name
     * 
     * @param assertName the name of the assertion as used in the definition
     * @param program the basic program in which to find the block
     * @return the BasicBlock in which the assertion occurs, or null if not found
     */
    public /*@ nullable */ BasicBlock findContainingBlock(/*@ non_null*/ Name assertName, /*@ non_null*/ BasicProgram program) {
        for (BasicBlock block: program.blocks) {
            for (JCStatement st: block.statements) {
                if ((st instanceof JmlStatementExpr) &&
                        ((JmlStatementExpr)st).token == JmlToken.ASSERT) {
                    JCExpression expr = ((JmlStatementExpr)st).expression;
                    if ((expr instanceof JCIdent) &&
                            ((JCIdent)expr).name == assertName) return block;
                }
            }
        }
        return null;
    }
    
    public /*@ nullable */ BasicBlock findContainingBlock(/*@ non_null*/ JCExpression expression, /*@ non_null*/ BasicProgram program) {
        for (BasicBlock block: program.blocks) {
            for (JCStatement st: block.statements) {
                if ((st instanceof JmlStatementExpr) &&
                        ((JmlStatementExpr)st).token == JmlToken.ASSERT) {
                    if (expression == ((JmlStatementExpr)st).expression) return block;
                }
            }
        }
        return null;
    }
    
    
}