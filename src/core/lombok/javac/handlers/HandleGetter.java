/*
 * Copyright (C) 2009-2013 The Project Lombok Authors.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok.javac.handlers;

import static lombok.javac.Javac.*;
import static lombok.javac.handlers.JavacHandlerUtil.*;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import lombok.AccessLevel;
import lombok.Delegate;
import lombok.Getter;
import lombok.core.AST.Kind;
import lombok.core.AnnotationValues;
import lombok.core.TransformationsUtil;
import lombok.javac.JavacAnnotationHandler;
import lombok.javac.JavacNode;
import lombok.javac.handlers.JavacHandlerUtil.FieldAccess;

import org.mangosdk.spi.ProviderFor;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCBinary;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCExpressionStatement;
import com.sun.tools.javac.tree.JCTree.JCIf;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCPrimitiveTypeTree;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCSynchronized;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;

/**
 * Handles the {@code lombok.Getter} annotation for javac.
 */
@ProviderFor(JavacAnnotationHandler.class)
public class HandleGetter extends JavacAnnotationHandler<Getter> {
	public void generateGetterForType(JavacNode typeNode, JavacNode errorNode, AccessLevel level, boolean checkForTypeLevelGetter) {
		if (checkForTypeLevelGetter) {
			if (hasAnnotation(Getter.class, typeNode)) {
				//The annotation will make it happen, so we can skip it.
				return;
			}
		}
		
		JCClassDecl typeDecl = null;
		if (typeNode.get() instanceof JCClassDecl) typeDecl = (JCClassDecl) typeNode.get();
		long modifiers = typeDecl == null ? 0 : typeDecl.mods.flags;
		boolean notAClass = (modifiers & (Flags.INTERFACE | Flags.ANNOTATION)) != 0;
		
		if (typeDecl == null || notAClass) {
			errorNode.addError("@Getter is only supported on a class, an enum, or a field.");
			return;
		}
		
		for (JavacNode field : typeNode.down()) {
			if (fieldQualifiesForGetterGeneration(field)) generateGetterForField(field, errorNode.get(), level, false);
		}
	}
	
	public boolean fieldQualifiesForGetterGeneration(JavacNode field) {
		if (field.getKind() != Kind.FIELD) return false;
		JCVariableDecl fieldDecl = (JCVariableDecl) field.get();
		//Skip fields that start with $
		if (fieldDecl.name.toString().startsWith("$")) return false;
		//Skip static fields.
		if ((fieldDecl.mods.flags & Flags.STATIC) != 0) return false;
		return true;
	}
	
	/**
	 * Generates a getter on the stated field.
	 * 
	 * Used by {@link HandleData}.
	 * 
	 * The difference between this call and the handle method is as follows:
	 * 
	 * If there is a {@code lombok.Getter} annotation on the field, it is used and the
	 * same rules apply (e.g. warning if the method already exists, stated access level applies).
	 * If not, the getter is still generated if it isn't already there, though there will not
	 * be a warning if its already there. The default access level is used.
	 * 
	 * @param fieldNode The node representing the field you want a getter for.
	 * @param pos The node responsible for generating the getter (the {@code @Data} or {@code @Getter} annotation).
	 */
	public void generateGetterForField(JavacNode fieldNode, DiagnosticPosition pos, AccessLevel level, boolean lazy) {
		if (hasAnnotation(Getter.class, fieldNode)) {
			//The annotation will make it happen, so we can skip it.
			return;
		}
		createGetterForField(level, fieldNode, fieldNode, false, lazy, List.<JCAnnotation>nil());
	}
	
	@Override public void handle(AnnotationValues<Getter> annotation, JCAnnotation ast, JavacNode annotationNode) {
		Collection<JavacNode> fields = annotationNode.upFromAnnotationToFields();
		deleteAnnotationIfNeccessary(annotationNode, Getter.class);
		deleteImportFromCompilationUnit(annotationNode, "lombok.AccessLevel");
		JavacNode node = annotationNode.up();
		Getter annotationInstance = annotation.getInstance();
		AccessLevel level = annotationInstance.value();
		boolean lazy = annotationInstance.lazy();
		if (level == AccessLevel.NONE) {
			if (lazy) {
				annotationNode.addWarning("'lazy' does not work with AccessLevel.NONE.");
			}
			return;
		}
		
		if (node == null) return;
		
		List<JCAnnotation> onMethod = unboxAndRemoveAnnotationParameter(ast, "onMethod", "@Getter(onMethod=", annotationNode);
		
		switch (node.getKind()) {
		case FIELD:
			createGetterForFields(level, fields, annotationNode, true, lazy, onMethod);
			break;
		case TYPE:
			if (!onMethod.isEmpty()) {
				annotationNode.addError("'onMethod' is not supported for @Getter on a type.");
			}
			if (lazy) annotationNode.addError("'lazy' is not supported for @Getter on a type.");
			generateGetterForType(node, annotationNode, level, false);
			break;
		}
	}
	
	private void createGetterForFields(AccessLevel level, Collection<JavacNode> fieldNodes, JavacNode errorNode, boolean whineIfExists, boolean lazy, List<JCAnnotation> onMethod) {
		for (JavacNode fieldNode : fieldNodes) {
			createGetterForField(level, fieldNode, errorNode, whineIfExists, lazy, onMethod);
		}
	}
	
	private void createGetterForField(AccessLevel level,
			JavacNode fieldNode, JavacNode source, boolean whineIfExists, boolean lazy, List<JCAnnotation> onMethod) {
		if (fieldNode.getKind() != Kind.FIELD) {
			source.addError("@Getter is only supported on a class or a field.");
			return;
		}
		
		JCVariableDecl fieldDecl = (JCVariableDecl)fieldNode.get();
		
		if (lazy) {
			if ((fieldDecl.mods.flags & Flags.PRIVATE) == 0 || (fieldDecl.mods.flags & Flags.FINAL) == 0) {
				source.addError("'lazy' requires the field to be private and final.");
				return;
			}
			if (fieldDecl.init == null) {
				source.addError("'lazy' requires field initialization.");
				return;
			}
		}
		
		String methodName = toGetterName(fieldNode);
		
		if (methodName == null) {
			source.addWarning("Not generating getter for this field: It does not fit your @Accessors prefix list.");
			return;
		}
		
		for (String altName : toAllGetterNames(fieldNode)) {
			switch (methodExists(altName, fieldNode, false, 0)) {
			case EXISTS_BY_LOMBOK:
				return;
			case EXISTS_BY_USER:
				if (whineIfExists) {
					String altNameExpl = "";
					if (!altName.equals(methodName)) altNameExpl = String.format(" (%s)", altName);
					source.addWarning(
						String.format("Not generating %s(): A method with that name already exists%s", methodName, altNameExpl));
				}
				return;
			default:
			case NOT_EXISTS:
				//continue scanning the other alt names.
			}
		}
		
		long access = toJavacModifier(level) | (fieldDecl.mods.flags & Flags.STATIC);
		
		injectMethod(fieldNode.up(), createGetter(access, fieldNode, fieldNode.getTreeMaker(), source.get(), lazy, onMethod));
	}
	
	private JCMethodDecl createGetter(long access, JavacNode field, TreeMaker treeMaker, JCTree source, boolean lazy, List<JCAnnotation> onMethod) {
		JCVariableDecl fieldNode = (JCVariableDecl) field.get();
		
		// Remember the type; lazy will change it
		JCExpression methodType = copyType(treeMaker, fieldNode);
		// Generate the methodName; lazy will change the field type
		Name methodName = field.toName(toGetterName(field));
		
		List<JCStatement> statements;
		JCTree toClearOfMarkers = null;
		if (lazy) {
			toClearOfMarkers = fieldNode.init;
			statements = createLazyGetterBody(treeMaker, field, source);
		} else {
			statements = createSimpleGetterBody(treeMaker, field);
		}
		
		JCBlock methodBody = treeMaker.Block(0, statements);
		
		List<JCTypeParameter> methodGenericParams = List.nil();
		List<JCVariableDecl> parameters = List.nil();
		List<JCExpression> throwsClauses = List.nil();
		JCExpression annotationMethodDefaultValue = null;
		
		List<JCAnnotation> nonNulls = findAnnotations(field, TransformationsUtil.NON_NULL_PATTERN);
		List<JCAnnotation> nullables = findAnnotations(field, TransformationsUtil.NULLABLE_PATTERN);
		
		List<JCAnnotation> delegates = findDelegatesAndRemoveFromField(field);
		
		List<JCAnnotation> annsOnMethod = copyAnnotations(onMethod).appendList(nonNulls).appendList(nullables);
		if (isFieldDeprecated(field)) {
			annsOnMethod = annsOnMethod.prepend(treeMaker.Annotation(chainDots(field, "java", "lang", "Deprecated"), List.<JCExpression>nil()));
		}
		
		JCMethodDecl decl = recursiveSetGeneratedBy(treeMaker.MethodDef(treeMaker.Modifiers(access, annsOnMethod), methodName, methodType,
				methodGenericParams, parameters, throwsClauses, methodBody, annotationMethodDefaultValue), source);
		
		if (toClearOfMarkers != null) recursiveSetGeneratedBy(toClearOfMarkers, null);
		decl.mods.annotations = decl.mods.annotations.appendList(delegates);
		
		return decl;
	}
	
	private static List<JCAnnotation> findDelegatesAndRemoveFromField(JavacNode field) {
		JCVariableDecl fieldNode = (JCVariableDecl) field.get();
		
		List<JCAnnotation> delegates = List.nil();
		for (JCAnnotation annotation : fieldNode.mods.annotations) {
			if (typeMatches(Delegate.class, field, annotation.annotationType)) {
				delegates = delegates.append(annotation);
			}
		}
		
		if (!delegates.isEmpty()) {
			ListBuffer<JCAnnotation> withoutDelegates = ListBuffer.lb();
			for (JCAnnotation annotation : fieldNode.mods.annotations) {
				if (!delegates.contains(annotation)) {
					withoutDelegates.append(annotation);
				}
			}
			fieldNode.mods.annotations = withoutDelegates.toList();
			field.rebuild();
		}
		return delegates;
	}
	
	private List<JCStatement> createSimpleGetterBody(TreeMaker treeMaker, JavacNode field) {
		return List.<JCStatement>of(treeMaker.Return(createFieldAccessor(treeMaker, field, FieldAccess.ALWAYS_FIELD)));
	}
	
	private static final String AR = "java.util.concurrent.atomic.AtomicReference";
	private static final String JLO = "java.lang.Object";
	private static final List<JCExpression> NIL_EXPRESSION = List.nil();
	
	private static final java.util.Map<Integer, String> TYPE_MAP;
	static {
		Map<Integer, String> m = new HashMap<Integer, String>();
		m.put(CTC_INT, "java.lang.Integer");
		m.put(CTC_DOUBLE, "java.lang.Double");
		m.put(CTC_FLOAT, "java.lang.Float");
		m.put(CTC_SHORT, "java.lang.Short");
		m.put(CTC_BYTE, "java.lang.Byte");
		m.put(CTC_LONG, "java.lang.Long");
		m.put(CTC_BOOLEAN, "java.lang.Boolean");
		m.put(CTC_CHAR, "java.lang.Character");
		TYPE_MAP = Collections.unmodifiableMap(m);
	}
	
	private List<JCStatement> createLazyGetterBody(TreeMaker maker, JavacNode fieldNode, JCTree source) {
		/*
		java.lang.Object value = this.fieldName.get();
		if (value == null) {
			synchronized (this.fieldName) {
				value = this.fieldName.get();
				if (value == null) {
					RawValueType actualValue = INITIALIZER_EXPRESSION;
					[IF PRIMITIVE]
					value = actualValue;
					[ELSE]
					value = actualValue == null ? this.fieldName : actualValue;
					[END IF]
					this.fieldName.set(value);
				}
			}
		}
		[IF PRIMITIVE]
		return (BoxedValueType) value;
		[ELSE]
		return (BoxedValueType) (value == this.fieldName ? null : value);
		[END IF]
		*/
		
		ListBuffer<JCStatement> statements = ListBuffer.lb();
		
		JCVariableDecl field = (JCVariableDecl) fieldNode.get();
		JCExpression copyOfRawFieldType = copyType(maker, field);
		JCExpression copyOfBoxedFieldType = null;
		field.type = null;
		boolean isPrimitive = false;
		if (field.vartype instanceof JCPrimitiveTypeTree) {
			String boxed = TYPE_MAP.get(((JCPrimitiveTypeTree)field.vartype).typetag);
			if (boxed != null) {
				isPrimitive = true;
				field.vartype = chainDotsString(fieldNode, boxed);
				copyOfBoxedFieldType = chainDotsString(fieldNode, boxed);
			}
		}
		if (copyOfBoxedFieldType == null) copyOfBoxedFieldType = copyType(maker, field);
		
		Name valueName = fieldNode.toName("value");
		Name actualValueName = fieldNode.toName("actualValue");
		
		/* java.lang.Object value = this.fieldName.get();*/ {
			JCExpression valueVarType = chainDotsString(fieldNode, JLO);
			statements.append(maker.VarDef(maker.Modifiers(0), valueName, valueVarType, callGet(fieldNode, createFieldAccessor(maker, fieldNode, FieldAccess.ALWAYS_FIELD))));
		}
		
		/* if (value == null) { */ {
			JCSynchronized synchronizedStatement;
			/* synchronized (this.fieldName) { */ {
				ListBuffer<JCStatement> synchronizedStatements = ListBuffer.lb();
				/* value = this.fieldName.get(); */ {
					JCExpressionStatement newAssign = maker.Exec(maker.Assign(maker.Ident(valueName), callGet(fieldNode, createFieldAccessor(maker, fieldNode, FieldAccess.ALWAYS_FIELD))));
					synchronizedStatements.append(newAssign);
				}
				
				/* if (value == null) { */ {
					ListBuffer<JCStatement> innerIfStatements = ListBuffer.lb();
					/* RawValueType actualValue = INITIALIZER_EXPRESSION; */ {
						innerIfStatements.append(maker.VarDef(maker.Modifiers(Flags.FINAL), actualValueName, copyOfRawFieldType, field.init));
					}
					/* [IF primitive] value = actualValue; */ {
						if (isPrimitive) {
							JCStatement statement = maker.Exec(maker.Assign(maker.Ident(valueName), maker.Ident(actualValueName)));
							innerIfStatements.append(statement);
						}
					}
					/* [ELSE] value = actualValue == null ? this.fieldName : actualValue; */ {
						if (!isPrimitive) {
							JCExpression actualValueIsNull = maker.Binary(CTC_EQUAL, maker.Ident(actualValueName), maker.Literal(CTC_BOT, null));
							JCExpression thisDotFieldName = createFieldAccessor(maker, fieldNode, FieldAccess.ALWAYS_FIELD);
							JCExpression ternary = maker.Conditional(actualValueIsNull, thisDotFieldName, maker.Ident(actualValueName));
							JCStatement statement = maker.Exec(maker.Assign(maker.Ident(valueName), ternary));
							innerIfStatements.append(statement);
						}
					}
					/* this.fieldName.set(value); */ {
						JCStatement statement = callSet(fieldNode, createFieldAccessor(maker, fieldNode, FieldAccess.ALWAYS_FIELD), maker.Ident(valueName));
						innerIfStatements.append(statement);
					}
					
					JCBinary isNull = maker.Binary(CTC_EQUAL, maker.Ident(valueName), maker.Literal(CTC_BOT, null));
					JCIf ifStatement = maker.If(isNull, maker.Block(0, innerIfStatements.toList()), null);
					synchronizedStatements.append(ifStatement);
				}
				
				synchronizedStatement = maker.Synchronized(createFieldAccessor(maker, fieldNode, FieldAccess.ALWAYS_FIELD), maker.Block(0, synchronizedStatements.toList()));
			}
			
			JCBinary isNull = maker.Binary(CTC_EQUAL, maker.Ident(valueName), maker.Literal(CTC_BOT, null));
			JCIf ifStatement = maker.If(isNull, maker.Block(0, List.<JCStatement>of(synchronizedStatement)), null);
			statements.append(ifStatement);
		}
		/* [IF PRIMITIVE] return (BoxedValueType) value; */ {
			if (isPrimitive) {
				statements.append(maker.Return(maker.TypeCast(copyOfBoxedFieldType, maker.Ident(valueName))));
			}
		}
		/* [ELSE] return (BoxedValueType) (value == this.fieldName ? null : value); */ {
			if (!isPrimitive) {
				JCExpression valueEqualsSelf = maker.Binary(CTC_EQUAL, maker.Ident(valueName), createFieldAccessor(maker, fieldNode, FieldAccess.ALWAYS_FIELD));
				JCExpression ternary = maker.Conditional(valueEqualsSelf, maker.Literal(CTC_BOT,  null), maker.Ident(valueName));
				JCExpression typeCast = maker.TypeCast(copyOfBoxedFieldType, maker.Parens(ternary));
				statements.append(maker.Return(typeCast));
			}
		}
		
		// update the field type and init last
		
		/*	private final java.util.concurrent.atomic.AtomicReference<Object> fieldName = new java.util.concurrent.atomic.AtomicReference<Object>(); */ {
			field.vartype = recursiveSetGeneratedBy(
					maker.TypeApply(chainDotsString(fieldNode, AR), List.<JCExpression>of(chainDotsString(fieldNode, JLO))), source);
			field.init = recursiveSetGeneratedBy(maker.NewClass(null, NIL_EXPRESSION, copyType(maker, field), NIL_EXPRESSION, null), source);
		}
		
		return statements.toList();
	}
	
	private JCMethodInvocation callGet(JavacNode source, JCExpression receiver) {
		TreeMaker maker = source.getTreeMaker();
		return maker.Apply(NIL_EXPRESSION, maker.Select(receiver, source.toName("get")), NIL_EXPRESSION);
	}
	
	private JCStatement callSet(JavacNode source, JCExpression receiver, JCExpression value) {
		TreeMaker maker = source.getTreeMaker();
		return maker.Exec(maker.Apply(NIL_EXPRESSION, maker.Select(receiver, source.toName("set")), List.<JCExpression>of(value)));
	}
	
	private JCExpression copyType(TreeMaker treeMaker, JCVariableDecl fieldNode) {
		return fieldNode.type != null ? treeMaker.Type(fieldNode.type) : fieldNode.vartype;
	}
}
