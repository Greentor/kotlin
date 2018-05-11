/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.asJava.elements

import com.intellij.openapi.diagnostic.Attachment
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import org.jetbrains.kotlin.asJava.LightClassGenerationSupport
import org.jetbrains.kotlin.asJava.classes.cannotModify
import org.jetbrains.kotlin.asJava.classes.lazyPub
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.load.java.descriptors.JavaClassConstructorDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.resolve.calls.model.*
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameUnsafe
import org.jetbrains.kotlin.resolve.source.getPsi
import org.jetbrains.kotlin.types.TypeUtils

private val LOG = Logger.getInstance("#org.jetbrains.kotlin.asJava.elements.lightAnnotations")

abstract class KtLightAbstractAnnotation(parent: PsiElement, computeDelegate: () -> PsiAnnotation) :
    KtLightElementBase(parent), PsiAnnotation, KtLightElement<KtCallElement, PsiAnnotation> {

    private val _clsDelegate: PsiAnnotation by lazyPub(computeDelegate)

    override val clsDelegate: PsiAnnotation
        get() {
            if (this !is KtLightNonSourceAnnotation)
                throw Exception("KtLightAbstractAnnotation clsDelegate requested " + this.javaClass)
//            Exception("KtLightAbstractAnnotation clsDelegate requested " + this.javaClass).printStackTrace(System.out)
            return _clsDelegate
        }

    override fun getNameReferenceElement() = clsDelegate.nameReferenceElement

    override fun getOwner() = parent as? PsiAnnotationOwner

    override fun getMetaData() = clsDelegate.metaData

    override fun getParameterList() = clsDelegate.parameterList

    override fun canNavigate(): Boolean = super<KtLightElementBase>.canNavigate()

    override fun canNavigateToSource(): Boolean = super<KtLightElementBase>.canNavigateToSource()

    override fun navigate(requestFocus: Boolean) = super<KtLightElementBase>.navigate(requestFocus)

    open fun fqNameMatches(fqName: String): Boolean = qualifiedName == fqName
}

private typealias AnnotationValueOrigin = () -> PsiElement?

class KtLightAnnotationForSourceEntry(
        private val qualifiedName: String,
        override val kotlinOrigin: KtCallElement,
        parent: PsiElement,
        computeDelegate: () -> PsiAnnotation
) : KtLightAbstractAnnotation(parent, computeDelegate) {

    override fun getQualifiedName() = qualifiedName

    open inner class LightElementValue<out D : PsiElement>(
            val delegate: D,
            private val parent: PsiElement,
            valueOrigin: AnnotationValueOrigin
    ) : PsiAnnotationMemberValue, PsiCompiledElement, PsiElement by delegate {
        override fun getMirror(): PsiElement = delegate

        val originalExpression: PsiElement? by lazyPub(valueOrigin)

        fun getConstantValue(): Any? {
            val expression = originalExpression as? KtExpression ?: return null
            val annotationEntry = this@KtLightAnnotationForSourceEntry.kotlinOrigin
            val context = LightClassGenerationSupport.getInstance(project).analyze(annotationEntry)
            return context[BindingContext.COMPILE_TIME_VALUE, expression]?.getValue(TypeUtils.NO_EXPECTED_TYPE)
        }

        override fun getReference() = references.singleOrNull()
        override fun getReferences() = originalExpression?.references.orEmpty()
        override fun getLanguage() = KotlinLanguage.INSTANCE
        override fun getNavigationElement() = originalExpression
        override fun isPhysical(): Boolean = false
        override fun getTextRange() = originalExpression?.textRange ?: TextRange.EMPTY_RANGE
        override fun getStartOffsetInParent() = originalExpression?.startOffsetInParent ?: 0
        override fun getParent() = parent
        override fun getText() = originalExpression?.text.orEmpty()
        override fun getContainingFile(): PsiFile? = if (originalExpression?.containingFile == kotlinOrigin.containingFile)
            kotlinOrigin.containingFile else delegate.containingFile

        override fun replace(newElement: PsiElement): PsiElement {
            val value = (newElement as? PsiLiteral)?.value as? String ?: return this
            val origin = originalExpression

            val exprToReplace =
                    if (origin is KtCallExpression /*arrayOf*/) {
                        unwrapArray(origin.valueArguments)
                    }
                    else {
                        origin as? KtExpression
                    } ?: return this
            exprToReplace.replace(KtPsiFactory(this).createExpression("\"${StringUtil.escapeStringCharacters(value)}\""))

            return this
        }
    }

    private fun getMemberValueAsCallArgument(memberValue: PsiElement, callHolder: KtCallElement): PsiElement? {
        val resolvedCall = callHolder.getResolvedCall() ?: return null
        val annotationConstructor = resolvedCall.resultingDescriptor
        val parameterName =
                memberValue.getNonStrictParentOfType<PsiNameValuePair>()?.name ?:
                memberValue.getNonStrictParentOfType<PsiAnnotationMethod>()?.takeIf { it.containingClass?.isAnnotationType == true }?.name ?:
                "value"

        val parameter = annotationConstructor.valueParameters.singleOrNull { it.name.asString() == parameterName } ?: return null
        val resolvedArgument = resolvedCall.valueArguments[parameter] ?: return null
        return when (resolvedArgument) {
            is DefaultValueArgument -> {
                val psi = parameter.source.getPsi()
                when (psi) {
                    is KtParameter -> psi.defaultValue
                    is PsiAnnotationMethod -> psi.defaultValue
                    else -> error("$psi of type ${psi?.javaClass}")
                }
            }

            is ExpressionValueArgument -> {
                val argExpression = resolvedArgument.valueArgument?.getArgumentExpression()
                argExpression?.asKtCall()
                ?: argExpression
                ?: error("resolvedArgument ($resolvedArgument) has no arg expression")
            }

            is VarargValueArgument ->
                memberValue.unwrapArray(resolvedArgument.arguments)
                ?: resolvedArgument.arguments.first().asElement().let {
                    (it as? KtValueArgument)
                            ?.takeIf {
                                it.getSpreadElement() != null ||
                                it.getArgumentName() != null ||
                                it.getArgumentExpression() is KtCollectionLiteralExpression
                            }
                            ?.getArgumentExpression() ?: it.parent
                }

            else -> error("resolvedArgument: ${resolvedArgument.javaClass} cant be processed")
        }
    }

    private fun PsiElement.unwrapArray(arguments: List<ValueArgument>): PsiElement? {
        val arrayInitializer = parent as? PsiArrayInitializerMemberValue ?: return null
        val exprIndex = arrayInitializer.initializers.indexOf(this)
        if (exprIndex < 0 || exprIndex >= arguments.size) return null
        return arguments[exprIndex].getArgumentExpression()
    }

    open inner class LightExpressionValue<out D : PsiExpression>(
            delegate: D,
            parent: PsiElement,
            valueOrigin: AnnotationValueOrigin
    ) : LightElementValue<D>(delegate, parent, valueOrigin), PsiExpression {
        override fun getType(): PsiType? = delegate.type
    }

    inner class LightPsiLiteral(
            delegate: PsiLiteralExpression,
            parent: PsiElement,
            valueOrigin: AnnotationValueOrigin
    ) : LightExpressionValue<PsiLiteralExpression>(delegate, parent, valueOrigin), PsiLiteralExpression {
        override fun getValue() = delegate.value
    }

    inner class LightClassLiteral(
            delegate: PsiClassObjectAccessExpression,
            parent: PsiElement,
            valueOrigin: AnnotationValueOrigin
    ) : LightExpressionValue<PsiClassObjectAccessExpression>(delegate, parent, valueOrigin), PsiClassObjectAccessExpression {
        override fun getType() = delegate.type
        override fun getOperand(): PsiTypeElement = delegate.operand
    }

    inner class LightArrayInitializerValue(
            delegate: PsiArrayInitializerMemberValue,
            parent: PsiElement,
            valueOrigin: AnnotationValueOrigin
    ) : LightElementValue<PsiArrayInitializerMemberValue>(delegate, parent, valueOrigin), PsiArrayInitializerMemberValue {
        private val _initializers by lazyPub {
            delegate.initializers.mapIndexed { i, memberValue ->
                wrapAnnotationValue(memberValue, this, {
                    originalExpression.let { ktOrigin ->
                        when {
                            ktOrigin is KtCallExpression
                                    && memberValue is PsiAnnotation
                                    && isAnnotationConstructorCall(ktOrigin, memberValue) -> ktOrigin
                            ktOrigin is KtValueArgumentList -> ktOrigin.arguments.getOrNull(i)?.getArgumentExpression()
                            ktOrigin is KtCallElement -> ktOrigin.valueArguments.getOrNull(i)?.getArgumentExpression()
                            ktOrigin is KtCollectionLiteralExpression -> ktOrigin.getInnerExpressions().getOrNull(i)
                            delegate.initializers.size == 1 -> ktOrigin
                            else -> null
                        }.also {
                            if (it == null)
                                LOG.error("error wrapping ${memberValue.javaClass} for ${ktOrigin?.javaClass} in ${ktOrigin?.containingFile}",
                                          Attachment(
                                              "source_fragments.txt",
                                              "origin: '${psiReport(ktOrigin)}', delegate: ${psiReport(delegate)}, parent: ${psiReport(parent)}"
                                          )
                                )
                        }
                    }
                })
            }.toTypedArray()
        }

        override fun getInitializers() = _initializers
    }

    private fun wrapAnnotationValue(value: PsiAnnotationMemberValue, parent: PsiElement, ktOrigin: AnnotationValueOrigin): PsiAnnotationMemberValue =
            when {
                value is PsiLiteralExpression -> LightPsiLiteral(value, parent, ktOrigin)
                value is PsiClassObjectAccessExpression -> LightClassLiteral(value, parent, ktOrigin)
                value is PsiExpression -> LightExpressionValue(value, parent, ktOrigin)
                value is PsiArrayInitializerMemberValue -> LightArrayInitializerValue(value, parent, ktOrigin)
                value is PsiAnnotation -> {
                    val origin = ktOrigin()
                    val ktCallElement = origin?.asKtCall()
                    val qualifiedName = value.qualifiedName
                    if (qualifiedName != null && ktCallElement != null)
                        KtLightAnnotationForSourceEntry(qualifiedName, ktCallElement, parent, { value })
                    else {
                        LOG.error("can't convert ${origin?.javaClass} to KtCallElement in ${origin?.containingFile} (value = ${value.javaClass})",
                                  Attachment("source_fragments.txt", "origin: '${psiReport(origin)}', value: '${psiReport(value)}'"))
                        LightElementValue(value, parent, ktOrigin) // or maybe create a LightErrorAnnotationMemberValue instead?
                    }
                }
                else -> LightElementValue(value, parent, ktOrigin)
            }

    override fun getName() = null

    private fun wrapAnnotationValue(value: PsiAnnotationMemberValue): PsiAnnotationMemberValue = wrapAnnotationValue(value, this, {
        getMemberValueAsCallArgument(value, kotlinOrigin)
    })

    override fun findAttributeValue(name: String?) =
        findDeclaredAttributeValue(name) ?: clsDelegate.findAttributeValue(name)?.let { wrapAnnotationValue(it) }


    override fun findDeclaredAttributeValue(name: String?): PsiAnnotationMemberValue? {
        val name = name ?: run { throw Exception("null value call") }
//        kotlinOrigin.getResolvedCall()!!.valueArguments.let {
//            println("attrubites for $name :" + it.entries.joinToString { it.key.name.asString() + " ->" + it.value.arguments.joinToString { it.getArgumentExpression()?.javaClass.toString() } })
//        }

        val resolvedCall = kotlinOrigin.getResolvedCall()!!
        val callEntry = resolvedCall.valueArguments.entries.find { (param, _) -> param.name.asString() == name }!!

        psiAnnotationMemberValue(this, callEntry.value.arguments, callEntry)?.let {
            return it
        }
        val argument = callEntry.value.arguments.firstOrNull()?.getArgumentExpression()
        println("cant process $argument of type ${argument?.javaClass} [${kotlinOrigin.text.lineSequence().firstOrNull()}]")

        return clsDelegate.findDeclaredAttributeValue(name)?.let { wrapAnnotationValue(it) }
    }



    override fun getNameReferenceElement(): PsiJavaCodeReferenceElement? {
        val reference = (kotlinOrigin as? KtAnnotationEntry)?.typeReference?.reference
                ?: (kotlinOrigin.calleeExpression as? KtNameReferenceExpression)?.reference
                ?: return null
        return KtLightPsiJavaCodeReferenceElement(
            kotlinOrigin.navigationElement,
            reference,
            { super.getNameReferenceElement()!! })
    }


    private val ktLightAnnotationParameterList by lazyPub { KtLightAnnotationParameterList() }

    override fun getParameterList(): PsiAnnotationParameterList = ktLightAnnotationParameterList

    inner class KtLightAnnotationParameterList() : KtLightElementBase(this),
        PsiAnnotationParameterList {
        override val kotlinOrigin get() = null

        private val _attributes: Array<PsiNameValuePair> by lazyPub {
            this@KtLightAnnotationForSourceEntry.kotlinOrigin.valueArguments.map { KtLightPsiNameValuePair(it as KtValueArgument) }
                .toTypedArray<PsiNameValuePair>()
        }

        override fun getAttributes(): Array<PsiNameValuePair> = _attributes

    }


    override fun delete() = kotlinOrigin.delete()

    override fun toString() = "@$qualifiedName"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other::class.java != this::class.java) return false
        return kotlinOrigin == (other as KtLightAnnotationForSourceEntry).kotlinOrigin
    }

    override fun hashCode() = kotlinOrigin.hashCode()

    override fun <T : PsiAnnotationMemberValue?> setDeclaredAttributeValue(attributeName: String?, value: T?) = cannotModify()
}

class KtLightNonSourceAnnotation(
    parent: PsiElement, clsDelegate: PsiAnnotation
) : KtLightAbstractAnnotation(parent, { clsDelegate }) {
    override val kotlinOrigin: KtAnnotationEntry? get() = null
    override fun getQualifiedName() = kotlinOrigin?.name ?: clsDelegate.qualifiedName
    override fun <T : PsiAnnotationMemberValue?> setDeclaredAttributeValue(attributeName: String?, value: T?) = cannotModify()
    override fun findAttributeValue(attributeName: String?) = clsDelegate.findAttributeValue(attributeName)
    override fun findDeclaredAttributeValue(attributeName: String?) = clsDelegate.findDeclaredAttributeValue(attributeName)
}

class KtLightNonExistentAnnotation(parent: KtLightElement<*, *>) : KtLightElementBase(parent), PsiAnnotation {
    override val kotlinOrigin get() = null
    override fun toString() = this.javaClass.name

    override fun <T : PsiAnnotationMemberValue?> setDeclaredAttributeValue(attributeName: String?, value: T?) = cannotModify()

    override fun getNameReferenceElement() = null
    override fun findAttributeValue(attributeName: String?) = null
    override fun getQualifiedName() = null
    override fun getOwner() = parent as? PsiAnnotationOwner
    override fun findDeclaredAttributeValue(attributeName: String?) = null
    override fun getMetaData() = null
    override fun getParameterList() = KtLightEmptyAnnotationParameterList(this)

    override fun canNavigate(): Boolean = super<KtLightElementBase>.canNavigate()

    override fun canNavigateToSource(): Boolean = super<KtLightElementBase>.canNavigateToSource()

    override fun navigate(requestFocus: Boolean) = super<KtLightElementBase>.navigate(requestFocus)
}

class KtLightEmptyAnnotationParameterList(parent: PsiElement) : KtLightElementBase(parent), PsiAnnotationParameterList {
    override val kotlinOrigin get() = null
    override fun getAttributes(): Array<PsiNameValuePair> = emptyArray()
}

class KtLightNullabilityAnnotation(member: KtLightElement<*, PsiModifierListOwner>, parent: PsiElement) : KtLightAbstractAnnotation(parent, {
    // searching for last because nullability annotations are generated after backend generates source annotations
    member.clsDelegate.modifierList?.annotations?.findLast {
        isNullabilityAnnotation(it.qualifiedName)
    } ?: KtLightNonExistentAnnotation(member)
}) {
    override fun fqNameMatches(fqName: String): Boolean {
        if (!isNullabilityAnnotation(fqName)) return false

        return super.fqNameMatches(fqName)
    }

    override val kotlinOrigin get() = null
    override fun <T : PsiAnnotationMemberValue?> setDeclaredAttributeValue(attributeName: String?, value: T?) = cannotModify()

    override fun findAttributeValue(attributeName: String?) = null

    override fun getQualifiedName(): String? = Nullable::class.java.name

    override fun getNameReferenceElement(): PsiJavaCodeReferenceElement? = null

    override fun findDeclaredAttributeValue(attributeName: String?) = null
}

internal fun isNullabilityAnnotation(qualifiedName: String?) = qualifiedName in backendNullabilityAnnotations

private val backendNullabilityAnnotations = arrayOf(Nullable::class.java.name, NotNull::class.java.name)

private fun KtElement.getResolvedCall(): ResolvedCall<out CallableDescriptor>? {
    val context = LightClassGenerationSupport.getInstance(this.project).analyze(this)
    return this.getResolvedCall(context)
}

private fun isAnnotationConstructorCall(callExpression: KtCallExpression, psiAnnotation: PsiAnnotation) =
    (callExpression.getResolvedCall()?.resultingDescriptor as? ClassConstructorDescriptor)?.constructedClass?.fqNameUnsafe?.toString() == psiAnnotation.qualifiedName

private fun PsiElement.asKtCall(): KtCallElement? = (this as? KtElement)?.getResolvedCall()?.call?.callElement as? KtCallElement

private fun psiReport(psiElement: PsiElement?): String {
    if (psiElement == null) return "null"
    val text = try {
        psiElement.text
    }
    catch (e: Exception) {
        "${e.javaClass.simpleName}:${e.message}"
    }
    return "${psiElement.javaClass.canonicalName}[$text]"
}

private fun psiAnnotationMemberValue(
    lightParent: PsiElement,
    valueArguments: List<ValueArgument>,
    resolvedCall: Map.Entry<ValueParameterDescriptor, ResolvedValueArgument>?
): PsiAnnotationMemberValue? {
    val valueArgument = valueArguments.firstOrNull()

    val arrayExpected = resolvedCall?.key?.type?.let { KotlinBuiltIns.isArray(it) } ?: false
    println("processing $valueArgument [${valueArgument?.getArgumentExpression()?.text}] arrayExpected = $arrayExpected")

    val argument = valueArgument?.getArgumentExpression() ?: return null
    if (arrayExpected && (argument is KtStringTemplateExpression || argument is KtConstantExpression || isAnnotation(argument) != null))
        return KtLightPsiArrayInitializerMemberValue(
            PsiTreeUtil.findCommonParent(valueArguments.map { it.getArgumentExpression() }) as KtElement,
            lightParent,
            { self -> valueArguments.mapNotNull { psiAnnotationMemberValue(self, listOf(it), null) } })

    return ktExpressionAsAnnotationMember(lightParent, argument)
}


fun ktExpressionAsAnnotationMember(lightParent: PsiElement, argument: KtExpression): PsiAnnotationMemberValue? {
    val argument = unwrapCall(argument)
    when (argument) {
        is KtStringTemplateExpression, is KtConstantExpression -> {
            println("processing KtLightPsiLiteral $argument [${argument.text}]")
            return KtLightPsiLiteral(argument, lightParent)
        }
        is KtCallExpression -> {
            val arguments = argument.valueArguments
            println("processing KtCallExpression $argument [${argument.text}] (${argument.calleeExpression}) KtCallExpression arguments:" + arguments)
            val annotationName = isAnnotation(argument.calleeExpression)
            if (annotationName != null) {
                return KtLightAnnotationForSourceEntry(annotationName, argument, lightParent, { TODO("not implemented") })
            }
            if (arguments.isNotEmpty())
                return KtLightPsiArrayInitializerMemberValue(
                    argument,
                    lightParent,
                    { self ->
                        arguments.mapNotNull {
                            psiAnnotationMemberValue(
                                self,
                                listOf(it),
                                argument.getResolvedCall()?.valueArguments?.entries?.find { (param, arg) -> arg.arguments.any { a -> a.getArgumentExpression() == it.getArgumentExpression() } }
                            )
                        }
                    })
        }
        is KtCollectionLiteralExpression -> {
            val arguments = argument.getInnerExpressions()
            println("processing KtCollectionLiteralExpression $argument [${argument.text}] arguments:" + arguments)
            if (arguments.isNotEmpty())
                return KtLightPsiArrayInitializerMemberValue(
                    argument,
                    lightParent,
                    { self -> arguments.mapNotNull { ktExpressionAsAnnotationMember(self, it) } })
        }
    }
    return null
}

private fun getNameReference(callee: KtExpression?): KtNameReferenceExpression? {
    if (callee is KtConstructorCalleeExpression)
        return callee.constructorReferenceExpression as? KtNameReferenceExpression
    return callee as? KtNameReferenceExpression
}

private fun unwrapCall(callee: KtExpression): KtExpression {
    val callee = if (callee is KtDotQualifiedExpression) {
        callee.lastChild as? KtCallExpression ?: callee
    } else callee
    return callee
}

private fun isAnnotation(callee: KtExpression?): String? {
    val callee = callee?.let { unwrapCall(it) }

    if (callee is KtCallExpression) {
        val resultingDescriptor = callee.getResolvedCall()?.resultingDescriptor
        if (resultingDescriptor is JavaClassConstructorDescriptor && (resultingDescriptor.constructedClass.source.getPsi() as? PsiClass)?.isAnnotationType == true) {
            println("callee ${callee.text} is annotation")
            return (resultingDescriptor.constructedClass.source.getPsi() as? PsiClass)?.qualifiedName
        } else {
            println("callee ${callee.text} is not annotation")
        }
    }
    getNameReference(callee)?.references?.forEach {
        val resovledElement = it.resolve()
        when (resovledElement) {
            is PsiClass -> if (resovledElement.isAnnotationType == true)
                return resovledElement.qualifiedName
            is KtClass -> if (resovledElement.isAnnotation())
                return resovledElement.fqName?.toString()
            is KtConstructor<*> -> {
                val containingClassOrObject = resovledElement.getContainingClassOrObject()
                if (containingClassOrObject.isAnnotation())
                    return containingClassOrObject.fqName?.asString()
            }
        }
    }
    return null
}