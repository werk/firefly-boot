package com.github.ahnfelt.firefly.language

import com.github.ahnfelt.firefly.language.Syntax._

import scala.collection.mutable.ArrayBuffer

case class ParseException(at : Location, message : String) extends RuntimeException(message + " " + at.toSuffix)

class Parser(file : String, tokens : ArrayBuffer[Token]) {

    val end = tokens.last

    private var offset = 0
    private def current =
        if(offset < tokens.length) tokens(offset) else end
    private def ahead =
        if(offset + 1 < tokens.length) tokens(offset + 1) else end
    private def aheadAhead =
        if(offset + 2 < tokens.length) tokens(offset + 2) else end
    private def skip(kind : TokenKind, value : String = null) : Token = {
        val c = current
        if(c.kind != kind) throw ParseException(c.at, "Expected " + kind + (if(value == null) "" else " " + value) + ", got " + c.raw)
        if(value != null && !c.rawIs(value)) throw ParseException(c.at, "Expected " + value + " got " + c.raw)
        offset += 1
        c
    }
    private def currentIsSeparator(kind : TokenKind) = {
        current.is(kind) || current.is(LSeparator)
    }
    private def skipSeparator(kind : TokenKind) = {
        if(current.is(LSeparator)) skip(LSeparator)
        else skip(kind)
    }

    def parseModule() : Module = {
        var result = Module(file, List(), List(), List(), List(), List())
        while(!current.is(LEnd)) {
            if(current.is(LLower) && (ahead.is(LAssign) || ahead.is(LColon))) {
                result = result.copy(lets = parseLetDefinition() :: result.lets)
            } else if(current.is(LNamespace) && ahead.is(LLower) && (aheadAhead.is(LAssign) || aheadAhead.is(LColon))) {
                val namespace = Some(skip(LNamespace).raw)
                result = result.copy(lets = parseLetDefinition(namespace) :: result.lets)
            } else if(current.is(LLower) && ahead.is(LBracketLeft)) {
                result = result.copy(functions = parseFunctionDefinition() :: result.functions)
            } else if(current.is(LNamespace) && ahead.is(LLower) && aheadAhead.is(LBracketLeft)) {
                val namespace = Some(skip(LNamespace).raw)
                result = result.copy(functions = parseFunctionDefinition(namespace) :: result.functions)
            } else if(current.is(LKeyword) && current.rawIs("trait")) {
                result = result.copy(traits = parseTraitDefinition() :: result.traits)
            } else if(current.is(LKeyword) && current.rawIs("instance")) {
                result = result.copy(instances = parseInstanceDefinition() :: result.instances)
            } else if(current.is(LKeyword) && current.rawIs("type")) {
                result = result.copy(types = parseTypeDefinition() :: result.types)
            } else {
                skip(LEnd)
            }
            if(!current.is(LEnd)) skipSeparator(LSemicolon)
        }
        Module(
            file = file,
            lets = result.lets.reverse,
            functions = result.functions.reverse,
            types = result.types.reverse,
            traits = result.traits.reverse,
            instances = result.instances.reverse
        )
    }

    def parseLetDefinition(scopeType : Option[String] = None) : DLet = {
        val nameToken = skip(LLower)
        val variableType = if(current.is(LColon)) {
            skip(LColon)
            parseType()
        } else Type(nameToken.at, "?", List())
        skip(LAssign)
        val value = parseTerm()
        DLet(nameToken.at, scopeType, nameToken.raw, variableType, value)
    }

    def parseFunctionDefinition(scopeType : Option[String] = None) : DFunction = {
        val signature = parseSignature(scopeType)
        val body = parseLambda(signature.parameters.size)
        DFunction(
            signature.at,
            scopeType,
            signature,
            body
        )
    }

    def parseSignature(scopeType : Option[String] = None) : Signature = {
        val nameToken = skip(LLower)
        val (generics, constraints) = if(current.rawIs("[")) parseTypeParameters() else List() -> List()
        val parameters = parseFunctionParameters()
        val returnType = parseOptionalType()
        Signature(nameToken.at, nameToken.raw, generics, constraints, parameters, returnType)
    }

    def parseTraitDefinition() : DTrait = {
        skip(LKeyword, "trait")
        val nameToken = skip(LUpper)
        val (generics, constraints) = if(!current.rawIs("[")) List() -> List() else parseTypeParameters()
        val generatorParameters = if(!current.rawIs("(")) List() else parseFunctionParameters()
        var methodGenerators = List[(String, Term)]()
        var methodDefaults = List[(String, Term)]()
        val methodSignatures = if(!current.rawIs("{")) List() else {
            var signatures = List[Signature]()
            skip(LBracketLeft, "{")
            while(!current.is(LBracketRight)) {
                val signature = parseSignature(Some(nameToken.raw))
                signatures ::= signature
                if(current.rawIs("{")) {
                    val generator = ahead.is(LKeyword) && ahead.rawIs("generate")
                    val body = parseLambda(signature.parameters.size, ignoreGenerateKeyword = true)
                    if(generator) {
                        methodGenerators ::= signature.name -> body
                    } else {
                        methodDefaults ::= signature.name -> body
                    }
                }
                if(!current.is(LBracketRight)) skipSeparator(LSemicolon)
            }
            skip(LBracketRight, "}")
            signatures
        }
        DTrait(
            nameToken.at,
            nameToken.raw,
            generics,
            constraints,
            generatorParameters,
            methodSignatures.reverse,
            methodDefaults.reverse,
            methodGenerators.reverse
        )
    }

    def parseInstanceDefinition() : DInstance = {
        skip(LKeyword, "instance")
        val nameToken = skip(LUpper)
        var typeArguments = List[Type]()
        skip(LBracketLeft, "[")
        val token = skip(LUpper)
        val (typeParameters, constraints) = if(!current.rawIs("[")) List() -> List() else parseTypeParameters()
        typeArguments ::= Type(token.at, token.raw, typeParameters.map(p => Type(token.at, p, List())))
        while(current.is(LComma)) {
            skip(LComma)
            typeArguments ::= parseType()
        }
        skip(LBracketRight, "]")
        val generatorArguments = if(!current.rawIs("(")) List() else parseFunctionArguments()
        val methods = if(!current.rawIs("{")) List() else {
            var definitions = List[DFunction]()
            skip(LBracketLeft, "{")
            while(!current.is(LBracketRight)) {
                definitions ::= parseFunctionDefinition(Some(nameToken.raw))
                if(!current.is(LBracketRight)) skipSeparator(LSemicolon)
            }
            skip(LBracketRight, "}")
            definitions
        }
        val traitType = Type(nameToken.at, nameToken.raw, typeArguments.reverse)
        DInstance(nameToken.at, typeParameters, constraints, traitType, generatorArguments, methods)
    }

    def parseTypeDefinition() : DType = {
        skip(LKeyword, "type")
        val nameToken = skip(LUpper)
        val (generics, constraints) = if(!current.rawIs("[")) List() -> List() else parseTypeParameters()
        val commonFields = if(!current.rawIs("(")) List() else parseFunctionParameters(allowMutable = true)
        val variants = if(!current.rawIs("{")) List(Variant(nameToken.at, nameToken.raw, List())) else {
            skip(LBracketLeft, "{")
            var reverseVariants = List[Variant]()
            while(!current.is(LBracketRight)) {
                val variantNameToken = skip(LUpper)
                val variantFields = if(!current.rawIs("(")) List() else parseFunctionParameters(allowMutable = true)
                reverseVariants ::= Variant(variantNameToken.at, variantNameToken.raw, variantFields)
                if(!current.is(LBracketRight)) skipSeparator(LSemicolon)
            }
            skip(LBracketRight, "}")
            reverseVariants.reverse
        }
        DType(nameToken.at, nameToken.raw, generics, constraints, commonFields, variants)
    }

    def parseTypeParameters() : (List[String], List[Constraint]) = {
        skip(LBracketLeft, "[")
        var parameters = List[String]()
        var constraints = List[Constraint]()
        while(!current.is(LBracketRight) && !current.is(LSemicolon)) {
            if(ahead.is(LBracketLeft)) {
                constraints ::= Constraint(parseType())
            } else {
                val parameterNameToken = skip(LUpper)
                parameters ::= parameterNameToken.raw
                while(current.is(LColon)) {
                    skip(LColon)
                    val t = parseType()
                    constraints ::= Constraint(t.copy(generics =
                        Type(t.at, parameterNameToken.raw, List()) :: t.generics
                    ))
                }
            }
            if(!current.is(LBracketRight)) skip(LComma)
        }
        if(current.is(LSemicolon)) {
            skip(LSemicolon)
            while(!current.is(LBracketRight)) {
                constraints ::= Constraint(parseType())
                if(!current.is(LBracketRight)) skip(LComma)
            }
        }
        skip(LBracketRight, "]")
        parameters.reverse -> constraints.reverse
    }

    def parseTypeArguments(parenthesis : Boolean = false) : List[Type] = {
        skip(LBracketLeft, if(parenthesis) "(" else "[")
        var types = List[Type]()
        while(!current.is(LBracketRight)) {
            types ::= parseType()
            if(!current.is(LBracketRight)) skip(LComma)
        }
        skip(LBracketRight, if(parenthesis) ")" else "]")
        types.reverse
    }

    def parseFunctionParameters(allowMutable : Boolean = false) : List[Parameter] = {
        var parameters = List[Parameter]()
        skip(LBracketLeft, "(")
        while(!current.is(LBracketRight)) {
            val mutable = allowMutable && current.is(LKeyword) && current.rawIs("mutable")
            if(mutable) skip(LKeyword)
            val parameterNameToken = skip(LLower)
            val parameterType = parseOptionalType()
            val default = if(!current.is(LAssign)) None else Some {
                skip(LAssign)
                parseTerm()
            }
            parameters ::= Parameter(parameterNameToken.at, mutable, parameterNameToken.raw, parameterType, default)
            if(!current.is(LBracketRight)) skipSeparator(LComma)
        }
        skip(LBracketRight, ")")
        parameters.reverse
    }

    def parseFunctionArguments() : List[Argument] = {
        skip(LBracketLeft, "(")
        var arguments = List[Argument]()
        while(!current.is(LBracketRight)) {
            val nameToken = if(current.is(LLower) && ahead.is(LAssign)) Some {
                val token = skip(LLower)
                skip(LAssign)
                token
            } else None
            val value = parseTerm()
            arguments ::= Argument(nameToken.map(_.at).getOrElse(value.at), nameToken.map(_.raw), value)
            if(!current.is(LBracketRight)) skipSeparator(LComma)
        }
        skip(LBracketRight, ")")
        arguments.reverse
    }

    def parseOptionalType() : Type = {
        val token = current
        if(token.is(LColon)) {
            skip(LColon)
            parseType()
        } else Type(token.at, "?", List())
    }

    def parseLambda(
        defaultParameterCount : Int = 0,
        ignoreGenerateKeyword : Boolean = false,
        allowColon : Boolean = false
    ) : ELambda = {
        val colon = allowColon && current.is(LColon)
        val token = if(colon) skip(LColon) else skip(LBracketLeft, "{")
        if(ignoreGenerateKeyword && current.is(LKeyword) && current.rawIs("generate")) skip(LKeyword)
        val result = if(current.is(LPipe)) {
            var cases = List[MatchCase]()
            while(current.is(LPipe)) {
                cases ::= parseCase()
            }
            cases.reverse
        } else if(current.is(LLower) && ahead.is(LComma, LArrowThick)) {
            var parameters = List[MatchPattern]()
            while(!current.is(LArrowThick)) {
                val parameterToken = skip(LLower)
                parameters ::= PVariable(parameterToken.at, Some(parameterToken.raw))
                if(!current.is(LArrowThick)) skip(LComma)
            }
            skip(LArrowThick)
            val term = parseStatements()
            List(MatchCase(token.at, parameters.reverse, None, term))
        } else {
            val term = parseStatements()
            val wildcards = new Wildcards()
            val e = wildcards.fixWildcards(term)
            val arguments = if(wildcards.seenWildcards != 0) {
                1.to(wildcards.seenWildcards).toList.map(i => PVariable(token.at, Some("_w" + i)))
            } else {
                1.to(defaultParameterCount).toList.map(_ => PVariable(token.at, None))
            }
            List(MatchCase(token.at, arguments, None, e))
        }
        if(!colon) skip(LBracketRight, "}")
        ELambda(token.at, result)
    }

    def parseCase() : MatchCase = {
        val token = skip(LPipe)
        var patterns = List[MatchPattern]()
        while(!current.is(LArrowThick) && !current.rawIs("{")) {
            patterns ::= parsePattern()
            if(!current.is(LArrowThick) && !current.rawIs("{")) skip(LComma)
        }
        val condition = if(!current.rawIs("{")) None else {
            skip(LBracketLeft)
            val term = parseStatements()
            skip(LBracketRight)
            Some(term)
        }
        skip(LArrowThick)
        val body = parseStatements()
        MatchCase(token.at, patterns.reverse, condition, body)
    }

    def parsePattern() : MatchPattern = {
        if(current.is(LWildcard)) {
            val token = skip(LWildcard)
            PVariable(token.at, None)
        } else if(current.is(LLower)) {
            val token = skip(LLower)
            PVariable(token.at, Some(token.raw))
        } else if(current.rawIs("(")) {
            val at = current.at
            val (fields, fieldPatterns) = parseRecordPattern().unzip
            PVariant(at, "Record$" + fields.mkString("$"), fieldPatterns)
        } else {
            val token = skip(LUpper)
            if(current.rawIs("(")) {
                var patterns = List[MatchPattern]()
                skip(LBracketLeft, "(")
                while(!current.is(LBracketRight)) {
                    patterns ::= parsePattern()
                    if(!current.is(LBracketRight)) skip(LComma)
                }
                skip(LBracketRight, ")")
                PVariant(token.at, token.raw, patterns.reverse)
            } else {
                if(current.is(LLower)) {
                    val asToken = skip(LLower)
                    PVariantAs(token.at, token.raw, Some(asToken.raw))
                } else if(current.is(LWildcard)) {
                    skip(LWildcard)
                    PVariantAs(token.at, token.raw, None)
                } else {
                    PVariant(token.at, token.raw, List())
                }
            }
        }
    }

    def parseType() : Type = {
        val leftTypes = if(current.rawIs("(") && ahead.is(LLower) && aheadAhead.is(LColon)) {
            val at = current.at
            val (fields, fieldTypes) = parseRecordType().unzip
            List(Type(at, "Record$" + fields.mkString("$"), fieldTypes))
        } else if(current.rawIs("(")) {
            parseTypeArguments(parenthesis = true)
        } else {
            val namespace = if(current.is(LNamespace)) skip(LNamespace).raw else ""
            val token = skip(LUpper)
            val arguments = if(!current.rawIs("[")) List() else parseTypeArguments()
            List(Type(token.at, namespace + token.raw, arguments))
        }
        if(!current.is(LArrowThick) && leftTypes.size == 1) leftTypes.head else {
            val arrowToken = skip(LArrowThick)
            val rightType = parseType()
            Type(arrowToken.at, "Function$" + leftTypes.size, leftTypes ++ List(rightType))
        }
    }

    def parseStatements() : Term =
        if(current.is(LBracketRight, LPipe)) EVariant(current.at, "Unit", List(), None) else {
            var result = parseStatement()
            while(currentIsSeparator(LSemicolon)) {
                val token = skipSeparator(LSemicolon)
                result = ESequential(token.at, result, parseStatement())
            }
            result
        }

    def parseStatement() : Term = {
        if(current.is(LKeyword) && (current.rawIs("let") || current.rawIs("mutable"))) parseLet()
        else if(current.is(LKeyword) && current.rawIs("function")) parseFunctions()
        else {
            val term = parseTerm()
            if(!current.is(LAssign) && !current.is(LAssignPlus, LAssignMinus, LAssignLink)) term else {
                val token =
                    if(current.is(LAssignPlus)) skip(LAssignPlus)
                    else if(current.is(LAssignMinus)) skip(LAssignMinus)
                    else if(current.is(LAssignLink)) skip(LAssignLink)
                    else skip(LAssign)
                val operator = token.raw.dropRight(1)
                val value = parseTerm()
                term match {
                    case EVariable(_, name) => EAssign(token.at, operator, name, value)
                    case e : EField => EAssignField(token.at, operator, e, value)
                    case _ => throw ParseException(token.at, "Only variables and fields are assignable")
                }
            }
        }
    }

    def parseLet() : Term = {
        val mutable = current.rawIs("mutable")
        if(mutable) skip(LKeyword, "mutable") else skip(LKeyword, "let")
        val nameToken = skip(LLower)
        val valueType = if(!current.is(LColon)) Type(nameToken.at, "?", List()) else {
            skip(LColon)
            parseType()
        }
        skipSeparator(LAssign)
        val value = parseTerm()
        skipSeparator(LSemicolon)
        val body = parseStatements()
        ELet(nameToken.at, mutable, nameToken.raw, valueType, value, body)
    }

    def parseFunctions() : Term = {
        val at = current.at
        var functions = List[LocalFunction]()
        while(current.rawIs("function")) {
            skip(LKeyword, "function")
            val signature = parseSignature()
            val body = parseLambda()
            functions ::= LocalFunction(signature, body)
            skipSeparator(LSemicolon)
        }
        val body = parseStatements()
        EFunctions(at, functions.reverse, body)
    }

    def parseTerm() : Term = {
        parseBinary(0)
    }

    val binaryOperators = Array(
        List("||"),
        List("&&"),
        List("!=", "=="),
        List("<=", ">=", "<", ">"),
        List("::"),
        List("++"),
        List("+", "-"),
        List("*", "/", "%"),
        List("^")
    )

    def parseBinary(level : Int) : Term = if(level >= binaryOperators.length) parseUnary() else {
        val operators = binaryOperators(level)
        var result = parseBinary(level + 1)
        if(current.is(LOperator)) {
            while(operators.exists(current.rawIs)) {
                val token = skip(LOperator)
                val right = parseBinary(level + 1)
                val arguments = List(Argument(result.at, None, result), Argument(right.at, None, right))
                result = ECall(token.at, EVariable(token.at, token.raw), List(), arguments)
            }
        }
        result
    }

    def parseUnary() : Term = {
        if(current.is(LOperator)) {
            val token = skip(LOperator)
            val term = parseUnary()
            val arguments = List(Argument(term.at, None, term))
            ECall(token.at, EVariable(token.at, token.raw), List(), arguments)
        } else {
            parseFieldsAndCalls()
        }
    }

    def parseFieldsAndCalls() : Term = {
        var result = parseAtom()
        while(current.is(LBracketLeft) || current.is(LColon) || current.is(LDot)) {
            if(current.is(LDot)) {
                skip(LDot)
                if(current.rawIs("{")) {
                    val term = parseAtom()
                    result = EPipe(term.at, result, term)
                } else if(current.is(LUpper, LNamespace)) {
                    result = parseCopy(result)
                } else {
                    val token = skip(LLower)
                    result = EField(token.at, result, token.raw)
                }
            } else {
                val at = current.at
                val typeArguments = if(!current.rawIs("[")) List() else parseTypeArguments()
                val arguments = if(!current.rawIs("(")) List() else parseFunctionArguments()
                var moreArguments = List[Argument]()
                var lastWasCurly = false
                while(current.rawIs("{") || current.is(LColon)) {
                    lastWasCurly = current.rawIs("{")
                    val lambda = parseLambda(allowColon = true)
                    moreArguments ::= Argument(lambda.at, None, lambda)
                }
                result = ECall(at, result, typeArguments, arguments ++ moreArguments.reverse)
                if(lastWasCurly && current.is(LLower)) {
                    val token = skip(LLower)
                    result = EField(token.at, result, token.raw)
                }
            }
        }
        result
    }

    def parseAtom() : Term = {
        if(current.is(LString)) { val token = skip(LString); EString(token.at, token.raw) }
        else if(current.is(LChar)) { val token = skip(LChar); EChar(token.at, token.raw) }
        else if(current.is(LInt)) { val token = skip(LInt); EInt(token.at, token.raw) }
        else if(current.is(LFloat)) { val token = skip(LFloat); EFloat(token.at, token.raw) }
        else if(current.is(LLower)) { val token = skip(LLower); EVariable(token.at, token.raw) }
        else if(current.is(LNamespace)) {
            val namespaceToken = skip(LNamespace)
            val extraNamespace = if(!current.is(LNamespace)) None else Some(skip(LNamespace).raw)
            val prefix = namespaceToken.raw + extraNamespace.getOrElse("")
            if(current.is(LLower)) { val token = skip(LLower); EVariable(token.at, prefix + token.raw) } else {
                parseVariant(prefix)
            }
        } else if(current.is(LUpper)) {
            parseVariant("")
        } else if(current.rawIs("{")) {
            parseLambda()
        } else if(current.rawIs("[")) {
            parseList()
        } else if(current.rawIs("(") && ahead.is(LLower) && aheadAhead.is(LAssign)) {
            ERecord(current.at, parseRecord())
        } else if(current.rawIs("(")) {
            skip(LBracketLeft, "(")
            val result = parseTerm()
            skip(LBracketRight, ")")
            result
        } else if(current.is(LWildcard)) {
            val token = skip(LWildcard)
            EWildcard(token.at, 0)
        } else {
            throw ParseException(current.at, "Expected atom, got " + current.raw)
        }
    }

    def parseVariant(prefix : String) : Term = {
        val token = skip(LUpper)
        val name = prefix + token.raw
        val typeArguments = if(!current.rawIs("[")) List() else parseTypeArguments()
        val arguments = if(!current.rawIs("(")) None else Some(parseFunctionArguments())
        EVariant(token.at, name, typeArguments, arguments)
    }

    def parseCopy(record : Term) : Term = {
        val namespace = if(!current.is(LNamespace)) "" else skip(LNamespace).raw
        val extraNamespace = if(!current.is(LNamespace)) "" else skip(LNamespace).raw
        val prefix = namespace + extraNamespace
        val token = skip(LUpper)
        val name = prefix + token.raw
        val fields = parseRecord()
        ECopy(token.at, name, record, fields)
    }

    def parseRecord() : List[Field] = {
        var fields = List[Field]()
        skip(LBracketLeft, "(")
        while(!current.is(LBracketRight)) {
            val fieldToken = skip(LLower)
            skip(LAssign)
            fields ::= Field(fieldToken.at, fieldToken.raw, parseTerm())
            if(!current.is(LBracketRight)) skipSeparator(LComma)
        }
        skip(LBracketRight, ")")
        fields.reverse
    }

    def parseRecordType() : List[(String, Type)] = {
        var fields = List[(String, Type)]()
        skip(LBracketLeft, "(")
        while(!current.is(LBracketRight)) {
            val fieldToken = skip(LLower)
            skipSeparator(LColon)
            fields ::= fieldToken.raw -> parseType()
            if(!current.is(LBracketRight)) skipSeparator(LComma)
        }
        skip(LBracketRight, ")")
        fields.reverse.sortBy(_._1)
    }

    def parseRecordPattern() : List[(String, MatchPattern)] = {
        var fields = List[(String, MatchPattern)]()
        skip(LBracketLeft, "(")
        while(!current.is(LBracketRight)) {
            val fieldToken = skip(LLower)
            skip(LAssign)
            fields ::= fieldToken.raw -> parsePattern()
            if(!current.is(LBracketRight)) skipSeparator(LComma)
        }
        skip(LBracketRight, ")")
        fields.reverse
    }

    def parseList() : Term = {
        var items = List[Term]()
        val at = skip(LBracketLeft, "[").at
        while(!current.rawIs("]")) {
            items ::= parseTerm()
            if(!current.rawIs("]")) skipSeparator(LComma)
        }
        skip(LBracketRight, "]")
        EList(at, items.reverse)
    }

}
