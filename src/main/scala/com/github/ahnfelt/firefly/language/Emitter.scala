package com.github.ahnfelt.firefly.language

import com.github.ahnfelt.firefly.language.Syntax._

class Emitter() {

    def emitModule(module : Module, otherModules : List[String]) : String = {
        val moduleNamespace = module.file.replace('\\', '/').reverse.takeWhile(_ != '/').reverse.takeWhile(_ != '.')
        val namespaces = module.types.map { definition =>
            val lets = module.lets.filter(_.namespace.contains(definition.name + "_"))
            val functions = module.functions.filter(_.namespace.contains(definition.name + "_"))
            if(lets.isEmpty && functions.isEmpty) None
            else Some(emitTypeMembers(definition.name, lets, functions))
        }
        val parts = List(
            List("package firefly"),
            List("import firefly.Firefly_Core._") ++ otherModules.map("import firefly." + _ + "._"),
            List("object " + moduleNamespace + " {"),
            if(
                module.functions.exists(f => f.namespace.isEmpty && f.signature.name == "main")
            ) List(emitMain()) else List(),
            module.types.map(emitTypeDefinition),
            module.lets.filter(_.namespace.isEmpty).map(emitLetDefinition(_)),
            module.functions.filter(_.namespace.isEmpty).map(emitFunctionDefinition(_)),
            module.functions.filter(f =>
                f.namespace.exists(f.signature.parameters.headOption.map(_.valueType.name + "_").contains)
            ).map(emitMethodImplicit),
            module.traits.map(emitTraitDefinition),
            module.instances.map(emitInstanceDefinition),
            namespaces.flatten,
            List(
                "}"
            ),
        )
        val allNamespaces = module.lets.flatMap(_.namespace) ++ module.functions.flatMap(_.namespace)
        allNamespaces.find(n => !module.types.exists(_.name + "_" == n)).foreach { n =>
            throw ParseException(Location(module.file, 1, 1), "No such type: " + n)
        }
        // Also, for each X_foo(bar: X, ...) method, generate implicit class X_foo(bar: X) { def foo(...) { ... } }
        parts.map(_.mkString("\n\n")).mkString("\n") + "\n"
    }

    def emitMain() = {
        """def main(arguments : Array[String]) : Unit = main(new System(arguments))"""
    }

    def emitTypeMembers(name : String, lets : List[DLet], functions : List[DFunction]) = {
        val strings =
            lets.map(emitLetDefinition(_)) ++
            functions.map(emitFunctionDefinition(_))
        "object " + name + " {\n\n" + strings.mkString("\n\n") + "\n\n}"
    }

    def emitTypeDefinition(definition : DType) : String = {
        val generics = emitTypeParameters(definition.generics)
        if(definition.variants.size == 1 && definition.variants.head.name == definition.name) {
            val fields = "(" + definition.commonFields.map(emitParameter).mkString(", ") + ")"
            "case class " + definition.name + generics + fields
        } else {
            val commonFields = if(definition.commonFields.isEmpty) "" else
                " {\n" + definition.commonFields.map(emitParameter).map("    val " + _ + "\n").mkString + "}"
            val variants = definition.variants.map(emitVariantDefinition(definition, _))
            val head = "sealed abstract class " + definition.name + generics + " extends Product with Serializable"
            head + commonFields + variants.map("\n" + _).mkString
        }
    }

    def emitLetDefinition(definition : DLet, mutable : Boolean = false) : String = {
        val typeAnnotation = emitTypeAnnotation(definition.variableType)
        val mutability = if(mutable) "var" else "val"
        mutability + " " + escapeKeyword(definition.name) + typeAnnotation + " = " + emitTerm(definition.value)
    }

    def emitFunctionDefinition(definition : DFunction, suffix : String = "") : String = {
        val signature = emitSignature(definition.signature, suffix)
        definition.body match {
            case ELambda(_, List(matchCase))
                if matchCase.patterns.forall { case PVariable(_, None) => true; case _ => false } =>
                val body = emitStatements(matchCase.body)
                signature + " = {\n" + body + "\n}"
            case _ =>
                val tuple = "(" + definition.signature.parameters.map(p => escapeKeyword(p.name)).mkString(", ") + ")"
                val cases = definition.body.cases.map(emitCase).mkString("\n")
                signature + " = " + tuple + " match {\n" + cases + "\n}"
        }
    }

    def emitMethodImplicit(definition : DFunction) : String = {
        val generics = emitTypeParameters(definition.signature.generics)
        val parameter = definition.signature.parameters.headOption.map(p =>
            escapeKeyword(p.name) + " : " + emitType(p.valueType)
        ).get
        val signature = emitSignature(definition.signature.copy(
            generics = List(),
            parameters = definition.signature.parameters.drop(1)
        ))
        val method = signature + " = " + definition.namespace.get.replace("_", ".") +
            escapeKeyword(definition.signature.name) +
            "(" + definition.signature.parameters.map(_.name).map(escapeKeyword).mkString(", ") + ")"
        "implicit class " + definition.namespace.get + definition.signature.name + generics +
        "(" + parameter + ") {\n\n" + method + "\n\n}"
    }

    def emitTraitDefinition(definition : DTrait) : String = {
        val generics = emitTypeParameters(definition.generics)
        val implicits = emitConstraints(definition.constraints)
        val parameters = if(definition.generatorParameters.isEmpty) ""
            else "(" + definition.generatorParameters.map(emitParameter).mkString(", ") + ")"
        val methods = if(definition.methods.isEmpty) "" else " {\n\nimport " + definition.name + "._\n\n" +
            definition.methods.map { signature =>
                val body = definition.methodDefaults.find(_._1 == signature.name).map { case (_, e) =>
                    " {\n" + emitStatements(e) + "\n}"
                }.orElse(definition.methodGenerators.find(_._1 == signature.name).map { case (_, e) =>
                    " {\n// TODO: Generate\n}"
                }).getOrElse {
                    ""
                }
                emitSignature(signature, "_m") + body
            }.mkString("\n\n") + "\n\n}"
        val methodWrappers = if(definition.methods.isEmpty) "" else " \n\n" + definition.methods.map { signature =>
            val t = Type(definition.at, definition.name, definition.generics.map(Type(definition.at, _, List())))
            emitSignature(signature.copy(
                generics = definition.generics ++ signature.generics,
                constraints = Constraint(t) :: definition.constraints ++ signature.constraints
            )) + " =\n    scala.Predef.implicitly[" + emitType(t) + "]." + escapeKeyword(signature.name) +
            "_m(" + signature.parameters.map(_.name).map(escapeKeyword).mkString(", ") + ")"
        }.mkString("\n\n") + "\n\n"
        "abstract class " + definition.name + generics + parameters + implicits + methods + "\n" +
        "object " + definition.name + " {" + methodWrappers + "}"
    }

    def emitInstanceDefinition(definition : DInstance) : String = {
        val signature = emitSignature(Signature(
            definition.at,
            definition.traitType.name + "_" + definition.hashCode().abs,
            definition.generics,
            definition.constraints,
            List(),
            definition.traitType
        ))
        val methods = " {\n\nimport " + definition.traitType.name + "._\n\n" +
            definition.methods.map(emitFunctionDefinition(_, "_m")).mkString("\n\n") + "\n\n}"
        val value = "new " + emitType(definition.traitType) + methods
        "implicit " + signature + " =\n    " + value
    }

    def emitVariantDefinition(typeDefinition : DType, definition : Variant) : String = {
        val generics = emitTypeParameters(typeDefinition.generics)
        val allFields = typeDefinition.commonFields ++ definition.fields
        val fields = "(" + allFields.map(emitParameter).mkString(", ") + ")"
        "case class " + definition.name + generics + fields + " extends " + typeDefinition.name + generics
    }

    def emitSignature(signature : Signature, suffix : String = "") : String = {
        val generics = emitTypeParameters(signature.generics)
        val parameters = "(" + signature.parameters.map(emitParameter).mkString(", ") + ")"
        val implicits =
            if(signature.constraints.isEmpty) ""
            else "(implicit " + signature.constraints.zipWithIndex.map { case (c, i) =>
                "i_" + i + " : " + emitType(c.representation)
            }.mkString(", ") + ")"
        val returnType = emitTypeAnnotation(signature.returnType)
        "def " + escapeKeyword(signature.name) + suffix + generics + parameters + implicits + returnType
    }

    def emitParameter(parameter : Parameter) : String = {
        val mutability = if(parameter.mutable) "var " else ""
        val defaultValue = parameter.default.map(f => " = " + emitTerm(f)).getOrElse("")
        mutability + escapeKeyword(parameter.name) + emitTypeAnnotation(parameter.valueType) + defaultValue
    }

    def emitConstraints(constraints : List[Constraint]) : String = if(constraints.isEmpty) "" else {
        val pairs = constraints.map(_.representation).map(emitType).zipWithIndex
        "(implicit " + pairs.map { case (k, v) => "i_" + v + " : " + k }.mkString(", ") + ")"
    }

    def emitTypeParameters(generics : List[String]) = {
        if(generics.isEmpty) "" else "[" + generics.mkString(", ") + "]"
    }

    def emitTypeAnnotation(t : Type) : String = {
        if(t.name == "?") "" else " : " + emitType(t)
    }

    def emitType(t : Type) : String = {
        if(t.name.startsWith("Function$")) {
            emitType(t.copy(name = t.name.replace("$", "")))
        } else if(t.name.startsWith("Record$")) {
            "{" + t.name.split('$').drop(1).toList.zip(t.generics).map { case (field, fieldType) =>
                "val " + escapeKeyword(field) + " : " + emitType(fieldType)
            }.mkString("; ") + "}"
        } else {
            val generics = if(t.generics.isEmpty) "" else "[" + t.generics.map(emitType).mkString(", ") + "]"
            t.name.replace("_", ".") + generics
        }
    }

    def emitStatements(term : Term) : String = term match {
        case EFunctions(at, functions, body) =>
            val functionStrings = functions.map(f => emitFunctionDefinition(DFunction(at, None, f.signature, f.body)))
            functionStrings.mkString("\n") + "\n" + emitStatements(body)
        case ELet(at, mutable, name, valueType, value, body) =>
            emitLetDefinition(DLet(at, None, name, valueType, value), mutable) + ";\n" + emitStatements(body)
        case ESequential(at, before, after) =>
            emitStatements(before) + ";\n" + emitStatements(after)
        case EAssign(at, operator, name, value) =>
            escapeKeyword(name) + " " + operator + " " + emitTerm(value)
        case EAssignField(at, operator, field, value) =>
            emitTerm(field) + " " + operator + " " + emitTerm(value)
        case _ => emitTerm(term)
    }

    def emitTerm(term : Term) : String = term match {
        case EString(at, value) => value
        case EChar(at, value) => value
        case EInt(at, value) => value
        case EFloat(at, value) => value
        case EVariable(at, name) => escapeKeyword(name.replace("_", "."))
        case EList(at, items) => "List(" + items.map(emitTerm).mkString(", ") + ")"
        case EVariant(at, name, typeArguments, arguments) =>
            val generics = if(typeArguments.isEmpty) "" else "[" + typeArguments.map(emitType).mkString(", ") + "]"
            name.replace("_", ".") + generics + "(" + arguments.map(emitTerm).mkString(", ") + ")"
        case ECopy(at, name, record, fields) =>
            val fieldCode = fields.map { case (l, e) => escapeKeyword(l) + " = " + emitTerm(e) }.mkString(", ")
            emitTerm(record) + ".copy(" + fieldCode + ")"
        case EField(at, record, field) => emitTerm(record) + "." + escapeKeyword(field)
        case ELambda(at, List(MatchCase(_, patterns, body))) if(patterns.forall(_.isInstanceOf[PVariable])) =>
            val parameters =
                patterns.map(_.asInstanceOf[PVariable].name.map(escapeKeyword).getOrElse("_")).mkString(", ")
            "{(" + parameters + ") =>\n" + emitStatements(body) + "\n}"
        case ELambda(at, cases) =>
            val casesString = cases.map(emitCase).mkString("\n")
            "{\n" + casesString + "\n}"
        case EPipe(at, value, function) =>
            "pipe_dot(" + emitTerm(value) + ")(" + emitTerm(function) + ")"
        case ECall(at, EVariable(_, operator), List(), List(value)) if !operator.head.isLetter =>
            "(" + operator + emitTerm(value) + ")"
        case ECall(at, EVariable(_, operator), List(), List(left, right)) if !operator.head.isLetter =>
            "(" + emitTerm(left) + " " + operator + " " + emitTerm(right) + ")"
        case ECall(at, function, typeArguments, arguments) =>
            val generics = if(typeArguments.isEmpty) "" else "[" + typeArguments.map(emitType).mkString(", ") + "]"
            emitTerm(function) + generics + "(" + arguments.map(emitTerm).mkString(", ") + ")"
        case ERecord(at, fields) =>
            if(fields.isEmpty) "{}" else {
                val list = fields.map { case (field, value) => "val " + escapeKeyword(field) + " = " + emitTerm(value) }
                "new {\n" + list.mkString(";\n") + ";\n}"
            }
        case EWildcard(at, index) =>
            if(index == 0) throw ParseException(at, "Unbound wildcard")
            "_w" + index
        case _ : EFunctions | _ : ELet | _ : ESequential | _ : EAssign | _ : EAssignField =>
            "{\n" + emitStatements(term) + "\n}"
    }

    def emitCase(matchCase : MatchCase) = {
        val patterns = matchCase.patterns.map(emitPattern).mkString(", ")
        "case (" + patterns + ") =>\n" + emitStatements(matchCase.body)
    }

    def emitPattern(pattern : MatchPattern) : String = pattern match {
        case PVariable(at, name) => name.map(escapeKeyword).getOrElse("_")
        case PVariant(at, name, patterns) =>
            name + "(" + patterns.map(emitPattern).mkString(", ") + ")"
        case PVariantAs(at, name, variable) =>
            escapeKeyword(variable) + " : " + name
    }

    def escapeKeyword(word : String) = if(keywords(word)) word + "_" else word

    val keywords = """
abstract
case
catch
class
def
do
else
extends
false
final
finally
for
forSome
if
implicit
import
lazy
match
new
null
object
override
package
private
protected
return
sealed
super
this
throw
trait
true
try
type
val
var
while
with
yield
scala
java
    """.linesIterator.map(_.trim).filter(_.nonEmpty).toSet

}
