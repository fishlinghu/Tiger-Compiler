package parser.semantic.ir

import com.sun.source.doctree.AttributeTree
import parser.syntactic.NonTerminal
import parser.semantic.ParseStream
import parser.semantic.SemanticException
import parser.semantic.symboltable.Attribute
import parser.syntactic.Rule
import scanner.TokenType
import parser.semantic.symboltable.Symbol
import parser.semantic.symboltable.SymbolTable

import scanner.TokenType.*
import java.lang.reflect.Type

class IrGenerator(val parseStream: ParseStream,
                  var currentSymbolTable: SymbolTable) {

    val ir = LinearIr()

    fun takeAction(rule: Rule): Symbol {
        if (rule == Rule.getRuleForExpansion(NonTerminal.TYPE_DECLARATION, TokenType.TYPE, ID, EQ, NonTerminal.TYPE, SEMI)) {
            generateTypeDeclaration()
        } else if (rule == Rule.getRuleForExpansion(NonTerminal.VAR_DECLARATION, VAR, NonTerminal.ID_LIST, COLON, NonTerminal.TYPE, NonTerminal.OPTIONAL_INIT, SEMI)) {
            generateVarDeclaration()
        } else if (rule == Rule.getRuleForExpansion(NonTerminal.FUNCT_DECLARATION, FUNC, ID, LPAREN, NonTerminal.PARAM_LIST, RPAREN, NonTerminal.RET_TYPE, BEGIN, NonTerminal.STAT_SEQ, END, SEMI)) {
            generateFunctionDeclaration()
        }
    }

    fun generateTypeDeclaration() {
        val newType = nextIdAsSymbol()

        val type = calculateType(parseStream.nextRule())

        newType.putAttribute(Attribute.TYPE, type)

        currentSymbolTable.insert(newType)
    }

    fun calculateType(parseRuleUsed: Rule): ExpressionType {
        if (parseRuleUsed == Rule.getRuleForExpansion(NonTerminal.TYPE, NonTerminal.TYPE_ID)) {
            return calculateBasicType()

        } else if (parseRuleUsed == Rule.getRuleForExpansion(NonTerminal.TYPE, ARRAY, LBRACK, INTLIT, RBRACK, OF, NonTerminal.TYPE_ID)) {
            return calculateArrayType()

        } else if (parseRuleUsed == Rule.getRuleForExpansion(NonTerminal.TYPE, ID)) {
            return calculateUserDefinedType()
        } else {
            throw RuntimeException("could not recognize rule for parsing type")
        }
    }


    fun calculateBasicType(): ExpressionType {
        val basicType = parseStream.nextParsableToken().grammarSymbol as TokenType

        if (basicType == TokenType.INTTYPEID) {
            return IntegerExpressionType()
        } else if (basicType == TokenType.FLOATTYPEID) {
            return FloatExpressionType()
        } else {
            throw RuntimeException("could not recognize basic type")
        }
    }

    fun calculateArrayType(): ExpressionType {
        val lengthToken = parseStream.nextParsableToken()

        val arrayLength: Int
        if (lengthToken.grammarSymbol == TokenType.INTLIT && lengthToken.text != null) {
            arrayLength = lengthToken.text.toInt()
        } else {
            throw RuntimeException("array length token should be int literal but could not be parsed")
        }

        val baseType = calculateType(parseStream.nextRule())

        return ArrayExpressionType(baseType, arrayLength)
    }

    fun calculateUserDefinedType(): ExpressionType {
        val typeToken = parseStream.nextParsableToken()

        if (typeToken.grammarSymbol == ID && typeToken.text != null) {
            val typeSymbol = currentSymbolTable.lookup(typeToken.text)

            return typeSymbol.getAttribute(Attribute.TYPE) as ExpressionType
        } else {
            throw RuntimeException("token should have TokenType ID to be parsed as symbol")
        }
    }

    fun generateVarDeclaration() {
        val newVars = calculateVarList()

        val type = calculateType(parseStream.nextRule())

        newVars.forEach {
            it.putAttribute(Attribute.TYPE, type)
            currentSymbolTable.insert(it)
        }

        if (hasOptionalInit()) {
            val initialValue = generateConst()

            newVars.forEach {
                generateOptionalInit(it, initialValue)
            }
        }
    }

    private fun hasOptionalInit() = parseStream.nextRule() == Rule.getRuleForExpansion(NonTerminal.OPTIONAL_INIT, ASSIGN, NonTerminal.CONST)

    fun calculateVarList(): List<Symbol> {
        val varList: MutableList<Symbol> = mutableListOf()

        do {
            val idToken = parseStream.nextParsableToken()
            if (idToken.grammarSymbol == ID && idToken.text != null) {
                varList.add(Symbol(idToken.text))
            }
        } while (parseStream.nextRule() == Rule.getRuleForExpansion(NonTerminal.ID_LIST_TAIL, COMMA, NonTerminal.ID_LIST))

        return varList
    }

    fun generateOptionalInit(symbolToAssign: Symbol, valueAssigned: Symbol) {
        if (valueAssigned.getAttribute(Attribute.TYPE) == symbolToAssign.getAttribute(Attribute.TYPE)) {

            ir.emit(ThreeAddressCode(symbolToAssign, IrOperation.ASSIGN, valueAssigned, null))
        }
    }

    fun generateFunctionDeclaration() {
        val functionSymbol = nextIdAsSymbol()
        currentSymbolTable.insert(functionSymbol)

        val functionType = calculateFunctionType()
        functionSymbol.putAttribute(Attribute.TYPE, functionType)

        val functionStartLabel = currentSymbolTable.newLabel()
        functionSymbol.putAttribute(Attribute.FUNCTION_START_LABEL, functionStartLabel)
        ir.emit(functionStartLabel)

        currentSymbolTable = currentSymbolTable.createChildScope(functionSymbol)

        generateStatementSequence()
    }

    fun calculateFunctionType(): FunctionExpressionType {
        val params = calculateParamTypes()

        val returnType: ExpressionType

        if (parseStream.nextRule() == Rule.getRuleForExpansion(NonTerminal.RET_TYPE, COLON, NonTerminal.TYPE)) {
            returnType = calculateType(parseStream.nextRule())

        } else {
            returnType = VoidExpressionType()
        }

        return FunctionExpressionType(params, returnType)
    }

    fun calculateParamTypes(): Array<ExpressionType> {
        val params: MutableList<ExpressionType> = mutableListOf()
        if (parseStream.nextRule() == Rule.getRuleForExpansion(NonTerminal.PARAM_LIST, NonTerminal.PARAM, NonTerminal.PARAM_LIST_TAIL)) {
            do {
                // Skipping param name (an ID) in parse stream.
                parseStream.nextParsableToken()

                val paramType = calculateType(parseStream.nextRule())
                params.add(paramType)

            } while (parseStream.nextRule() == Rule.getRuleForExpansion(NonTerminal.PARAM_LIST_TAIL, COMMA, NonTerminal.PARAM, NonTerminal.PARAM_LIST_TAIL))
        }

        return params.toTypedArray()
    }

    fun generateStatementSequence() {

        do {
            generateStatement()
        } while (parseStream.nextRule() == Rule.getRuleForExpansion(NonTerminal.STAT_SEQ_TAIL, NonTerminal.STAT_SEQ))
    }

    fun generateStatement() {
        val statementParseRule = parseStream.nextRule()

        if (statementParseRule == Rule.getRuleForExpansion(NonTerminal.STAT, NonTerminal.LVALUE, NonTerminal.STAT_ID)) {
            val lhs = generateName()

            if (parseStream.nextRule() == Rule.getRuleForExpansion(NonTerminal.STAT_ID, LPAREN, NonTerminal.EXPR_LIST, RPAREN, SEMI)) {
                generateFunctionCallAsStatement(lhs)
            } else {
                generateAssignmentStatement(lhs)
            }
        }
    }

    fun generateFunctionCallAsStatement(function: Symbol) {
        val parameterTypes = (function.getAttribute(Attribute.TYPE) as FunctionExpressionType).params

        val arguments = generateArguments(parameterTypes)

        ir.emit(FunctionCallCode(IrOperation.CALLR, function, *arguments))
    }

    fun generateArguments(paramTypes: Array<ExpressionType>): Array<Symbol> {
        val argumentsList: MutableList<Symbol> = mutableListOf()

        if (parseStream.nextRule() == Rule.getRuleForExpansion(NonTerminal.EXPR_LIST, NonTerminal.EXPR, NonTerminal.EXPR_LIST_TAIL)) {
            do {
                argumentsList.add(generateExpression())
            } while (parseStream.nextRule() == Rule.getRuleForExpansion(NonTerminal.EXPR_LIST_TAIL, COMMA, NonTerminal.EXPR, NonTerminal.EXPR_LIST_TAIL))
        }

        val argumentTypes = argumentsList.map { it.getAttribute(Attribute.TYPE) }

        if (argumentsList.size != paramTypes.size) {
            throw SemanticException("expected params $paramTypes, found $argumentTypes")
        }

        paramTypes.forEachIndexed { i, paramType ->
            if (paramType != argumentTypes[i]) {
                throw SemanticException("expected params $paramTypes, found $argumentTypes")
            }
        }

        return argumentsList.toTypedArray()
    }

    fun generateAssignmentStatement(lhs: Symbol) {
        val rhsResult: Symbol

    }

    fun generateName(): Symbol {
        val symbolToken = parseStream.nextParsableToken()
        if (symbolToken.grammarSymbol == ID && symbolToken.text != null) {

            val baseSymbol = currentSymbolTable.lookup(symbolToken.text)

            if (parseStream.nextRule() == Rule.getRuleForExpansion(NonTerminal.LVALUE_TAIL, LBRACK, NonTerminal.EXPR, RBRACK)) {
                return generateArrayAccess(baseSymbol)

            } else {
                return baseSymbol
            }

        } else {
            throw RuntimeException("token should be ID to be parsed as symbol")
        }
    }

    private fun generateArrayAccess(baseSymbol: Symbol): Symbol {
        if (baseSymbol.getAttribute(Attribute.TYPE) !is ArrayExpressionType) {
            throw SemanticException("symbol ${baseSymbol.name} is not an array")
        }
        val arrayIndex = generateArrayIndex()

        val arrayElement = currentSymbolTable.newTemporary()

        ir.emit(ThreeAddressCode(arrayElement, IrOperation.ADD, arrayIndex, baseSymbol))
        return arrayElement
    }

    private fun generateArrayIndex(): Symbol {
        val expressionValue = generateExpression()
        if (!isIntegerExpressionType(expressionValue)) {
            throw SemanticException("array index must be integer expression")
        }

        return expressionValue
    }



    private fun isIntegerExpressionType(leftOperand: Symbol) = leftOperand.getAttribute(Attribute.TYPE) is IntegerExpressionType

    fun generateConst(): Symbol {
        val literalToken = parseStream.nextParsableToken()

        val symbol = currentSymbolTable.newTemporary()

        if (literalToken.grammarSymbol == TokenType.INTLIT && literalToken.text != null) {
            symbol.putAttribute(Attribute.TYPE, IntegerExpressionType())
            symbol.putAttribute(Attribute.LITERAL_VALUE, literalToken.text.toInt())

        } else if (literalToken.grammarSymbol == TokenType.FLOATLIT && literalToken.text != null) {
            symbol.putAttribute(Attribute.TYPE, FloatExpressionType())
            symbol.putAttribute(Attribute.LITERAL_VALUE, literalToken.text.toFloat())
        }

        symbol.putAttribute(Attribute.IS_LITERAL, true)

        return symbol
    }

    fun nextIdAsSymbol(): Symbol {
        val parsableToken = parseStream.nextParsableToken()

        if (parsableToken.grammarSymbol == ID && parsableToken.text != null) {
            return Symbol(parsableToken.text)
        } else {
            throw RuntimeException("token should have TokenType ID to be parsed as symbol")
        }

    }
}
