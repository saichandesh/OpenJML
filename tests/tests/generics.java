package tests;

import org.jmlspecs.openjml.JmlSpecs;

public class generics extends TCBase {


    @Override
    public void setUp() throws Exception {
//        noCollectDiagnostics = true;
//        jmldebug = true;
        super.setUp();
    }

    /** Test something very simple with no errors*/
    public void testSimpleGeneric() {
        addMockFile("$A/A.spec","public class A<T> { /*@ non_null*/ T t; /*@ non_null pure*/ T item(); }");
        helpTCF("A.java","public class A<T> { T t; T item() { return t; }}");
    }

    /** Test with a binary class*/
    public void testBinaryGeneric() {
        JmlSpecs.instance(context).setSpecsPath(new String[]{"$A","$B","$CP"});
        addMockFile("$A/java/util/Collection.spec","package java.util; public interface Collection<E> { public boolean add(E t); }");
        helpTCF("A.java","public class A<X> { java.util.Collection<X> t; }");
    }

    /** Test mismatched type parameters*/
    public void testSimpleGeneric1() {
        addMockFile("$A/A.spec","public class A {  }");
        //specs.printDatabase();
        helpTCF("A.java","public class A<T> { T t; T item() { return t; }}"
                ,"/$A/A.spec:1: The type A in the specification matches a Java type A<T> with a different number of type arguments",8
                );
    }

    /** Test with a binary class*/
    public void testBinaryGeneric2() {
        JmlSpecs.instance(context).setSpecsPath(new String[]{"$A","$B","$CP"});
        addMockFile("$A/java/util/Collection.spec","public interface Collection {  }");
        helpTCF("A.java","public class A<X> { java.util.Collection<X> t; }"
                ,"/$A/java/util/Collection.spec:1: The specification type named Collection (java.util.Collection) with 0 type parameters matches a Java type with 1 type parameters",8
                );
    }

    /** Test with a binary class*/
    public void testBinaryGeneric3() {
        JmlSpecs.instance(context).setSpecsPath(new String[]{"$A","$B","$CP"});
        addMockFile("$A/java/util/Collection.spec","public interface Collection<Z> {  }");
        helpTCF("A.java","public class A<X> { java.util.Collection<X> t; }"
                ,"/$A/java/util/Collection.spec:1: The specification type named Collection (java.util.Collection) has a type parameters named Z but the Java declaration has that type parameter named E",29
                );
    }

    /** Test with a binary class*/
    public void testBinaryGeneric4() {
        JmlSpecs.instance(context).setSpecsPath(new String[]{"$A","$B","$CP"});
        addMockFile("$A/java/util/Collection.spec","public interface Collection<E,Z> {  }");
        helpTCF("A.java","public class A<X> { java.util.Collection<X> t; }"
                ,"/$A/java/util/Collection.spec:1: The specification type named Collection (java.util.Collection) with 2 type parameters matches a Java type with 1 type parameters",8
                );
    }
    
    public void testMethod() {
        helpTCF("A.java","public class A<X> {\n  <T>T doit(T t) { return t; } }"
                );
        
    }
    
    public void testMethod2() {
        addMockFile("$A/java/util/Vector.spec","public class Vector<E> { \npublic <T> T[] toArray(T[] t); }");
        helpTCF("A.java","public class A<X> { java.util.Vector<X> t; }"
                ,"/$A/java/util/Vector.spec:2: The method toArray in the specification matches a Java method <T>toArray(T[]) with different modifiers: synchronized",16
                );
        
    }

 
}