package firefly
import firefly.Firefly_Core._

import firefly.Syntax_._
object Resolver_ {

case class Resolver(variables : Firefly_Core.Map[Firefly_Core.String, Firefly_Core.String], variants : Firefly_Core.Map[Firefly_Core.String, Firefly_Core.String], types : Firefly_Core.Map[Firefly_Core.String, Firefly_Core.String], traits : Firefly_Core.Map[Firefly_Core.String, Firefly_Core.String])

def make() = {
def core(name : Firefly_Core.String) : Firefly_Core.Pair[Firefly_Core.String, Firefly_Core.String] = {
Firefly_Core.Pair(name, ("ff:core/Core." + name))
}
Resolver(variables = Firefly_Core.List("if", "while", "do", "panic", "try", "log").map(core).toMap, variants = Firefly_Core.List("True", "False", "Some", "None", "Pair", "Array", "ArrayBuilder", "List", "ListBuilder", "Map", "MapBuilder", "Set", "SetBuilder", "Empty", "Link", "Unit").map(core).toMap, types = Firefly_Core.List("Int", "String", "Char", "Bool", "Option", "Pair", "Array", "ArrayBuilder", "List", "Map", "Set", "System", "FileSystem", "Unit").map(core).toMap, traits = Firefly_Core.Map())
}

def fail[T](at : Syntax_.Location, message : Firefly_Core.String) : T = {
Firefly_Core.panic(((message + " ") + at.show))
}
implicit class Resolver_extend0(self : Resolver) {

def resolveModule(module : Syntax_.Module, otherModules : Firefly_Core.List[Syntax_.Module]) : Syntax_.Module = {
val moduleNamespace = module.file.replace('\\', '/').reverse.takeWhile({(_w1) =>
(_w1 != '/')
}).reverse.takeWhile({(_w1) =>
(_w1 != '.')
});
val self2 = self.processImports(module.imports, otherModules);
val self3 = self2.processDefinitions(module, Firefly_Core.None());
module.copy(types = module.types.map({(_w1) =>
self3.resolveTypeDefinition(_w1)
}), traits = module.traits.map({(_w1) =>
self3.resolveTraitDefinition(_w1)
}), instances = module.instances.map({(_w1) =>
self3.resolveInstanceDefinition(_w1)
}), extends_ = module.extends_.map({(_w1) =>
self3.resolveExtendDefinition(_w1)
}), lets = module.lets.map({(_w1) =>
self3.resolveLetDefinition(_w1)
}), functions = module.functions.map({(_w1) =>
self3.resolveFunctionDefinition(_w1)
}))
}

def processImports(imports : Firefly_Core.List[Syntax_.DImport], modules : Firefly_Core.List[Syntax_.Module]) : Resolver = {
var resolver = self;
imports.each({(import_) =>
pipe_dot(modules.find({(_w1) =>
(_w1.file.dropRight(3) == import_.file)
}))({
case (Firefly_Core.Some(module)) =>
resolver = resolver.processDefinitions(module, Firefly_Core.Some(import_.alias))
case (Firefly_Core.None()) =>
fail(import_.at, ("No such module: " + import_.file))
})
});
resolver
}

def processDefinitions(module : Syntax_.Module, importAlias : Firefly_Core.Option[Firefly_Core.String]) : Resolver = {
def entry(name : Firefly_Core.String, unqualified : Firefly_Core.Bool) : Firefly_Core.List[Firefly_Core.Pair[Firefly_Core.String, Firefly_Core.String]] = {
val full = ((module.file.dropRight(3) + ".") + name);
pipe_dot(importAlias)({
case (Firefly_Core.None()) =>
Firefly_Core.List(Firefly_Core.Pair(name, name))
case (Firefly_Core.Some(alias)) if unqualified =>
Firefly_Core.List(Firefly_Core.Pair(((alias + ".") + name), full), Firefly_Core.Pair(name, full))
case (Firefly_Core.Some(alias)) =>
Firefly_Core.List(Firefly_Core.Pair(((alias + ".") + name), full))
})
}
val lets = module.lets.flatMap({(_w1) =>
entry(_w1.name, Firefly_Core.False())
}).toMap;
val functions = module.functions.flatMap({(_w1) =>
entry(_w1.signature.name, Firefly_Core.False())
}).toMap;
val traitMethods = module.traits.flatMap({(_w1) =>
_w1.methods
}).flatMap({(_w1) =>
entry(_w1.name, Firefly_Core.False())
}).toMap;
val traits = module.traits.flatMap({(_w1) =>
entry(_w1.name, Firefly_Core.True())
}).toMap;
val types = module.types.flatMap({(_w1) =>
entry(_w1.name, Firefly_Core.True())
}).toMap;
val variants = module.types.flatMap({(_w1) =>
_w1.variants
}).flatMap({(_w1) =>
entry(_w1.name, Firefly_Core.True())
}).toMap;
Resolver(variables = (((self.variables ++ lets) ++ functions) ++ traitMethods), variants = (self.variants ++ variants), types = (self.types ++ types), traits = (self.traits ++ traits))
}

def resolveTypeDefinition(definition : Syntax_.DType) : Syntax_.DType = {
val generics = definition.generics.map({(g) =>
Firefly_Core.Pair(g, g)
}).toMap;
val self2 = self.copy(types = (self.types ++ generics));
definition.copy(constraints = definition.constraints.map({(c) =>
c.copy(representation = self2.resolveType(c.representation))
}), commonFields = definition.commonFields.map({(f) =>
f.copy(valueType = self2.resolveType(f.valueType), default = f.default.map({(_w1) =>
self2.resolveTerm(_w1)
}))
}), variants = definition.variants.map({(v) =>
v.copy(fields = v.fields.map({(f) =>
f.copy(valueType = self2.resolveType(f.valueType), default = f.default.map({(_w1) =>
self2.resolveTerm(_w1)
}))
}))
}))
}

def resolveTraitDefinition(definition : Syntax_.DTrait) : Syntax_.DTrait = {
definition
}

def resolveInstanceDefinition(definition : Syntax_.DInstance) : Syntax_.DInstance = {
definition
}

def resolveExtendDefinition(definition : Syntax_.DExtend) : Syntax_.DExtend = {
val generics = definition.generics.map({(g) =>
Firefly_Core.Pair(g, g)
}).toMap;
val self2 = self.copy(types = (self.types ++ generics), variables = (self.variables + Firefly_Core.Pair(definition.name, definition.name)));
definition.copy(constraints = definition.constraints.map({(c) =>
c.copy(representation = self2.resolveType(c.representation))
}), type_ = self2.resolveType(definition.type_), methods = definition.methods.map({(_w1) =>
self2.resolveFunctionDefinition(_w1)
}))
}

def resolveLetDefinition(definition : Syntax_.DLet) : Syntax_.DLet = {
val self2 = self.copy(variables = (self.variables + Firefly_Core.Pair(definition.name, definition.name)));
definition.copy(variableType = self.resolveType(definition.variableType), value = self.resolveTerm(definition.value))
}

def resolveFunctionDefinition(definition : Syntax_.DFunction) : Syntax_.DFunction = {
val local = self.resolveFunction(Syntax_.LocalFunction(definition.signature, definition.body));
definition.copy(signature = local.signature, body = local.body)
}

def resolveTerm(term : Syntax_.Term) : Syntax_.Term = (term) match {
case (_ : Syntax_.EString) =>
term
case (_ : Syntax_.EChar) =>
term
case (_ : Syntax_.EInt) =>
term
case (_ : Syntax_.EFloat) =>
term
case (e : Syntax_.EVariable) =>
self.variables.get(e.name).map({(_w1) =>
e.copy(name = _w1)
}).else_({() =>
Firefly_Core.if_(e.name.headOption.any({(_w1) =>
_w1.isLetter
}), {() =>
fail(e.at, ("No such variable: " + e.name))
}).else_({() =>
term
})
})
case (Syntax_.EList(at, items)) =>
Syntax_.EList(at, items.map({(_w1) =>
self.resolveTerm(_w1)
}))
case (Syntax_.EVariant(at, name, typeArguments, arguments)) =>
Syntax_.EVariant(at = at, name = self.variants.get(name).else_({() =>
fail(at, ("No such variant: " + name))
}), typeArguments = typeArguments.map({(_w1) =>
self.resolveType(_w1)
}), arguments = arguments.map({(_w1) =>
_w1.map({(a) =>
a.copy(value = self.resolveTerm(a.value))
})
}))
case (Syntax_.EVariantIs(at, name, typeArguments)) =>
Syntax_.EVariantIs(at = at, name = self.variants.get(name).else_({() =>
fail(at, ("No such variant: " + name))
}), typeArguments = typeArguments.map({(_w1) =>
self.resolveType(_w1)
}))
case (Syntax_.ECopy(at, name, record, arguments)) =>
Syntax_.ECopy(at = at, name = self.variants.get(name).else_({() =>
fail(at, ("No such variant: " + name))
}), record = self.resolveTerm(record), arguments = arguments.map({(f) =>
f.copy(value = self.resolveTerm(f.value))
}))
case (e : Syntax_.EField) =>
e.copy(record = self.resolveTerm(e.record))
case (Syntax_.ELambda(at, Syntax_.Lambda(lambdaAt, cases))) =>
Syntax_.ELambda(at, Syntax_.Lambda(lambdaAt, cases.map({(_w1) =>
self.resolveCase(_w1)
})))
case (Syntax_.EPipe(at, value, function)) =>
Syntax_.EPipe(at = at, value = self.resolveTerm(value), function = self.resolveTerm(function))
case (Syntax_.ECall(at, function, typeArguments, arguments)) =>
Syntax_.ECall(at = at, function = self.resolveTerm(function), typeArguments = typeArguments.map({(_w1) =>
self.resolveType(_w1)
}), arguments = arguments.map({(a) =>
a.copy(value = self.resolveTerm(a.value))
}))
case (Syntax_.ERecord(at, fields)) =>
Syntax_.ERecord(at = at, fields = fields.map({(f) =>
f.copy(value = self.resolveTerm(f.value))
}))
case (e : Syntax_.EWildcard) =>
Firefly_Core.if_((e.index == 0), {() =>
fail(e.at, "Unbound wildcard")
});
e
case (Syntax_.EFunctions(at, functions, body)) =>
val functionMap = functions.map({(_w1) =>
_w1.signature.name
}).map({(name) =>
Firefly_Core.Pair(name, name)
}).toMap;
val self2 = self.copy(variables = (self.variables ++ functionMap));
Syntax_.EFunctions(at = at, functions = functions.map({(_w1) =>
self2.resolveFunction(_w1)
}), body = self2.resolveTerm(body))
case (e : Syntax_.ELet) =>
val self2 = self.copy(variables = (self.variables + Firefly_Core.Pair(e.name, e.name)));
e.copy(valueType = self.resolveType(e.valueType), value = self.resolveTerm(e.value), body = self2.resolveTerm(e.body))
case (Syntax_.ESequential(at, before, after)) =>
Syntax_.ESequential(at = at, before = self.resolveTerm(before), after = self.resolveTerm(after))
case (Syntax_.EAssign(at, operator, variable, value)) =>
Syntax_.EAssign(at = at, operator = operator, variable = self.variables.get(variable).else_({() =>
fail(at, ("No such variable: " + variable))
}), value = self.resolveTerm(value))
case (Syntax_.EAssignField(at, operator, record, field, value)) =>
Syntax_.EAssignField(at = at, operator = operator, record = self.resolveTerm(record), field = field, value = self.resolveTerm(value))
}

def resolveType(type_ : Syntax_.Type) : Syntax_.Type = (type_) match {
case (_ : Syntax_.TVariable) =>
type_
case (constructor : Syntax_.TConstructor) =>
val name = Firefly_Core.if_(constructor.name.contains("$"), {() =>
constructor.name
}).else_({() =>
self.types.get(constructor.name).else_({() =>
fail(constructor.at, ("No such type: " + constructor.name))
})
});
constructor.copy(name = name, generics = constructor.generics.map({(_w1) =>
self.resolveType(_w1)
}))
}

def resolveFunction(function : Syntax_.LocalFunction) : Syntax_.LocalFunction = {
val variableMap = function.signature.parameters.map({(_w1) =>
_w1.name
}).map({(name) =>
Firefly_Core.Pair(name, name)
}).toMap;
val typeMap = function.signature.generics.map({(name) =>
Firefly_Core.Pair(name, name)
}).toMap;
val self2 = self.copy(variables = (self.variables ++ variableMap), types = (self.types ++ typeMap));
val signature = function.signature.copy(constraints = function.signature.constraints.map({(c) =>
Syntax_.Constraint(self2.resolveType(c.representation))
}), parameters = function.signature.parameters.map({(p) =>
p.copy(valueType = self2.resolveType(p.valueType), default = p.default.map({(_w1) =>
self2.resolveTerm(_w1)
}))
}), returnType = self2.resolveType(function.signature.returnType));
val body = function.body.copy(cases = function.body.cases.map({(_w1) =>
self2.resolveCase(_w1)
}));
Syntax_.LocalFunction(signature, body)
}

def resolveCase(case_ : Syntax_.MatchCase) : Syntax_.MatchCase = {
def findVariables(pattern : Syntax_.MatchPattern) : Firefly_Core.Map[Firefly_Core.String, Firefly_Core.String] = (pattern) match {
case (Syntax_.PVariable(_, Firefly_Core.Some(name))) =>
Firefly_Core.Map(Firefly_Core.Pair(name, name))
case (Syntax_.PVariable(_, Firefly_Core.None())) =>
Firefly_Core.Map()
case (Syntax_.PVariant(_, _, patterns)) =>
patterns.map(findVariables).foldLeft(Firefly_Core.Map[Firefly_Core.String, Firefly_Core.String]())({(_w1, _w2) =>
(_w1 ++ _w2)
}).toMap
case (Syntax_.PVariantAs(_, _, variable)) =>
variable.toList.map({(x) =>
Firefly_Core.Pair(x, x)
}).toMap
case (Syntax_.PAlias(_, pattern, variable)) =>
(Firefly_Core.Map(Firefly_Core.Pair(variable, variable)) ++ findVariables(pattern))
}
val variableMap = case_.patterns.map(findVariables).foldLeft(Firefly_Core.Map[Firefly_Core.String, Firefly_Core.String]())({(_w1, _w2) =>
(_w1 ++ _w2)
}).toMap;
val self2 = self.copy(variables = (self.variables ++ variableMap));
Syntax_.MatchCase(at = case_.at, patterns = case_.patterns.map({(_w1) =>
self2.resolvePattern(_w1)
}), condition = case_.condition.map({(_w1) =>
self2.resolveTerm(_w1)
}), body = self2.resolveTerm(case_.body))
}

def resolvePattern(pattern : Syntax_.MatchPattern) : Syntax_.MatchPattern = (pattern) match {
case (p @ (_ : Syntax_.PVariable)) =>
p
case (Syntax_.PVariant(at, name, patterns)) =>
val newName = self.variants.get(name).else_({() =>
fail(at, ("No such variant: " + name))
});
val newPatterns = patterns.map({(_w1) =>
self.resolvePattern(_w1)
});
Syntax_.PVariant(at, newName, newPatterns)
case (Syntax_.PVariantAs(at, name, variable)) =>
val newName = self.variants.get(name).else_({() =>
fail(at, ("No such variant: " + name))
});
Syntax_.PVariantAs(at, newName, variable)
case (Syntax_.PAlias(at, pattern, variable)) =>
val newPattern = self.resolvePattern(pattern);
Syntax_.PAlias(at, newPattern, variable)
}

}


}
