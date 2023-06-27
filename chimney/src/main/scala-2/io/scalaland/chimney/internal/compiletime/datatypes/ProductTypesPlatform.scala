package io.scalaland.chimney.internal.compiletime.datatypes

import io.scalaland.chimney.internal.compiletime.DefinitionsPlatform

import scala.collection.compat.*
import scala.collection.immutable.ListMap

private[compiletime] trait ProductTypesPlatform extends ProductTypes { this: DefinitionsPlatform =>

  import c.universe.{internal as _, Transformer as _, *}

  protected object ProductType extends ProductTypesModule {

    object platformSpecific {

      def isParameterless(method: MethodSymbol): Boolean = method.paramLists.flatten.isEmpty

      def isDefaultConstructor(ctor: Symbol): Boolean =
        ctor.isPublic && ctor.isConstructor && isParameterless(ctor.asMethod)

      def isAccessor(accessor: MethodSymbol): Boolean =
        accessor.isPublic && isParameterless(accessor)

      // assuming isAccessor was tested earlier
      def isCaseClassField(field: MethodSymbol): Boolean =
        field.isCaseAccessor

      // assuming isAccessor was tested earlier
      def isJavaGetter(getter: MethodSymbol): Boolean =
        isGetterName(getter.name.toString)

      def isJavaSetter(setter: MethodSymbol): Boolean =
        setter.isPublic && setter.paramLists.size == 1 && setter.paramLists.head.size == 1 &&
          isSetterName(setter.asMethod.name.toString)

      def isVar(setter: Symbol): Boolean =
        setter.isPublic && setter.isTerm && setter.asTerm.name.toString.endsWith("_$eq")

      def isJavaSetterOrVar(setter: Symbol): Boolean =
        (setter.isMethod && isJavaSetter(setter.asMethod)) || isVar(setter)
    }

    import platformSpecific.*

    def isPOJO[A](implicit A: Type[A]): Boolean = {
      val sym = A.tpe.typeSymbol
      sym.isClass && !sym.isAbstract && sym.asClass.primaryConstructor.isPublic
    }
    def isCaseClass[A](implicit A: Type[A]): Boolean =
      isPOJO[A] && A.tpe.typeSymbol.asClass.isCaseClass
    def isCaseObject[A](implicit A: Type[A]): Boolean = {
      val sym = A.tpe.typeSymbol
      def isScala2Enum = sym.asClass.isCaseClass
      def isScala3Enum = sym.isStatic && sym.isFinal // paramless case in S3 cannot be checked for "case"
      sym.isPublic && sym.isModuleClass && (isScala2Enum || isScala3Enum)
    }
    def isJavaBean[A](implicit A: Type[A]): Boolean = {
      val mem = A.tpe.members
      isPOJO[A] && mem.exists(isDefaultConstructor) && mem.exists(isJavaSetterOrVar)
    }

    def parseExtraction[A: Type]: Option[Product.Extraction[A]] = {
      import Type.platformSpecific.*

      Some(
        Product.Extraction(
          ListMap.from[String, Existential[Product.Getter[A, *]]](
            Type[A].tpe.decls
              .to(List)
              .filterNot(isGarbageSymbol)
              .collect { case method if method.isMethod => method.asMethod }
              .filter(isAccessor)
              .map { getter =>
                val name = getDecodedName(getter)
                val tpe = ExistentialType(fromUntyped(returnTypeOf(Type[A].tpe, getter)))
                name -> tpe.mapK[Product.Getter[A, *]] { implicit Tpe: Type[tpe.Underlying] => _ =>
                  val termName = getter.asMethod.name.toTermName
                  Product.Getter[A, tpe.Underlying](
                    sourceType =
                      if (isCaseClassField(getter)) Product.Getter.SourceType.ConstructorVal
                      else if (isJavaGetter(getter)) Product.Getter.SourceType.JavaBeanGetter
                      else if (getter.isStable) Product.Getter.SourceType.ConstructorVal // Hmm...
                      else Product.Getter.SourceType.AccessorMethod,
                    get =
                      // TODO: handle pathological cases like getName[Unused]()()()
                      if (getter.asMethod.paramLists.isEmpty) (in: Expr[A]) => c.Expr[tpe.Underlying](q"$in.$termName")
                      else
                        (in: Expr[A]) =>
                          c.Expr[tpe.Underlying](
                            q"$in.$termName(...${getter.paramLists.map(_.map(_.asInstanceOf[Tree]))})"
                          )
                  )
                }
              }
          )
        )
      )
    }

    def parseConstructor[A: Type]: Option[Product.Constructor[A]] = if (
      isJavaBean[A] || isCaseObject[A] || isCaseClass[A]
    ) Some({
      import Type.platformSpecific.*

      val A = Type[A].tpe
      val sym = A.typeSymbol

      if (isJavaBean[A]) {
        val defaultConstructor =
          A.decls
            .to(List)
            .filterNot(isGarbageSymbol)
            .find(isDefaultConstructor)
            .map(_ => c.Expr[A](q"new $A()"))
            .getOrElse(assertionFailed(s"Expected default constructor for ${Type.prettyPrint[A]}"))

        val setters =
          A.decls
            .to(List)
            .filterNot(isGarbageSymbol)
            .collect { case m if m.isMethod => m.asMethod }
            .filter(isJavaSetterOrVar)
            .map { setter =>
              // Scala 3's JB setters _are_ methods ending with _= due to change in @BeanProperty behavior.
              // We have to drop that suffix to align names, so that comparing is possible.
              val n: String = getDecodedName(setter)
              val name = if (isVar(setter)) n.substring(0, n.length - "_$eq".length) else n

              val termName = setter.asTerm.name.toTermName
              val tpe = ExistentialType(fromUntyped(paramListsOf(Type[A].tpe, setter).flatten.head.typeSignature))
              (
                name,
                termName,
                tpe.mapK[Product.Parameter](_ =>
                  _ =>
                    Product.Parameter(
                      targetType = Product.Parameter.TargetType.SetterParameter,
                      defaultValue = None
                    )
                )
              )
            }

        val parameters: Product.Parameters = ListMap.from(setters.map { case (name, _, param) => name -> param })

        val termNames = setters.map { case (name, termName, _) => name -> termName }.toMap

        val constructor: Product.Arguments => Expr[A] = arguments => {
          val beanTermName: TermName =
            ExprPromise.provideFreshName[A](ExprPromise.NameGenerationStrategy.FromType, ExprPromise.UsageHint.None)

          val checkedArguments = checkArguments(parameters, arguments).map { case (name, e) =>
            ExistentialExpr.use(e) { implicit E: Type[e.Underlying] => expr =>
              q"$beanTermName.${termNames(name)}($expr)"
            }
          }.toList

          val statements = q"val $beanTermName: ${Type[A]} = $defaultConstructor" +: checkedArguments

          c.Expr(q"..$statements; $beanTermName")
        }

        Product.Constructor(parameters, constructor)
      } else if (isCaseObject[A]) {
        Product.Constructor(ListMap.empty, _ => c.Expr(q"${sym.asClass.module}"))
      } else {
        val primaryConstructor =
          Option(sym).filter(_.isClass).map(_.asClass.primaryConstructor).filter(_.isPublic).getOrElse {
            assertionFailed(s"Expected public constructor of ${Type.prettyPrint[A]}")
          }

        val defaultValues =
          primaryConstructor.typeSignature.paramLists.headOption.toList.flatten.zipWithIndex.collect {
            case (param, idx) if param.asTerm.isParamWithDefault =>
              val companion = sym.companion
              val scala2default = caseClassApplyDefaultScala2(idx + 1)
              val scala3default = caseClassApplyDefaultScala3(idx + 1)
              companion.typeSignature.decls
                .to(List)
                .collectFirst {
                  case method if getDecodedName(method) == scala2default =>
                    getDecodedName(param) -> q"${companion}.${TermName(scala2default)}"
                  case method if getDecodedName(method) == scala3default =>
                    getDecodedName(param) -> q"${companion}.${TermName(scala3default)}"
                }
                .head
          }.toMap

        val parametersRaw = paramListsOf(A, primaryConstructor).map { params =>
          params
            .map { param =>
              val name = getDecodedName(param)
              val tpe = ExistentialType(fromUntyped(param.typeSignatureIn(A)))
              name ->
                tpe.mapK { implicit Tpe: Type[tpe.Underlying] => _ =>
                  Product.Parameter(
                    Product.Parameter.TargetType.ConstructorParameter,
                    defaultValues.get(name).map { value =>
                      c.Expr[tpe.Underlying](value)
                    }
                  )
                }
            }
        }

        val parameters: Product.Parameters = ListMap.from(parametersRaw.flatten)

        val constructor: Product.Arguments => Expr[A] = arguments => {
          val unadaptedCheckedArguments = checkArguments(parameters, arguments)

          val checkedArguments = parametersRaw.map { params =>
            params.map { case (name, _) => unadaptedCheckedArguments(name).value.asInstanceOf[Expr[Any]] }
          }

          c.Expr[A](q"new $A(...$checkedArguments)")
        }

        Product.Constructor(parameters, constructor)
      }
    })
    else None

    private val getDecodedName = (s: Symbol) => s.name.decodedName.toString

    private val isGarbageSymbol = getDecodedName andThen isGarbage
  }
}
