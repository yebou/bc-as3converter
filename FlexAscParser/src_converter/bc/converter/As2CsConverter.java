package bc.converter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import bc.code.ListWriteDestination;
import bc.code.WriteDestination;
import bc.lang.BcClassDefinitionNode;
import bc.lang.BcFuncParam;
import bc.lang.BcFunctionDeclaration;
import bc.lang.BcFunctionTypeNode;
import bc.lang.BcInterfaceDefinitionNode;
import bc.lang.BcMetadata;
import bc.lang.BcTypeNode;
import bc.lang.BcVariableDeclaration;

public class As2CsConverter extends As2WhateverConverter
{
	private ListWriteDestination src;
	
	private void writeImports(WriteDestination dest, List<String> imports)	
	{
		List<String> sortedImports = new ArrayList<String>(imports);
		Collections.sort(sortedImports);
		
		for (String importString : sortedImports)
		{
			dest.writelnf("using %s;", importString);
		}
	}

	private void writeInterfaceFunctions(BcClassDefinitionNode bcClass)
	{
		List<BcFunctionDeclaration> functions = bcClass.getFunctions();
		for (BcFunctionDeclaration bcFunc : functions)
		{
			String type = bcFunc.hasReturnType() ? codeHelper.typeRef(bcFunc.getReturnType()) : "void";
			String name = codeHelper.identifier(bcFunc.getName());
			
			if (bcFunc.isConstructor())
			{
				continue;
			}
			
			src.writef("%s %s(", type, name);
			
			StringBuilder paramsBuffer = new StringBuilder();
			StringBuilder argsBuffer = new StringBuilder();
			List<BcFuncParam> params = bcFunc.getParams();
			int paramIndex = 0;
			for (BcFuncParam bcParam : params)
			{
				String paramType = type(bcParam.getType());
				String paramName = codeHelper.identifier(bcParam.getIdentifier());
				paramsBuffer.append(String.format("%s %s", paramType, paramName));
				argsBuffer.append(paramName);
				if (++paramIndex < params.size())
				{
					paramsBuffer.append(", ");
					argsBuffer.append(", ");
				}
			}
			
			src.write(paramsBuffer);
			src.writeln(");");
		}
	}

	@Override
	protected void writeClassDefinition(BcClassDefinitionNode bcClass, File outputDir) throws IOException
	{
		boolean isInterface = bcClass instanceof BcInterfaceDefinitionNode;
		
		String className = getClassName(bcClass);
		
		String packageName = bcClass.getPackageName();
		String subPath = packageName.replace(".", "/");
		
		File srcFileDir = new File(outputDir, subPath);
		if (!srcFileDir.exists())
		{
			boolean successed = srcFileDir.mkdirs();
			assert successed : srcFileDir.getAbsolutePath();
		}
		
		File outputFile = new File(srcFileDir, className + ".cs");
		
		BcMetadata metadata = bcClass.getMetadata();
		if (metadata != null)
		{
			if (metadata.contains("NoConversion"))
			{
				System.out.println("No conversion: " + bcClass.getName());
				return;
			}
			
			if (metadata.contains("ConvertOnce"))
			{
				if (outputFile.exists())
				{
					System.out.println("Convert once: " + bcClass.getName());
					return;
				}
			}			
		}
		
		src = new ListWriteDestination();		
		
		src.writeln("using System;");
		writeBlankLine(src);
		
		writeImports(src, getImports(bcClass));
		writeBlankLine(src);
		
		src.writeln("namespace " + codeHelper.namespace(bcClass.getPackageName()));
		writeBlockOpen(src);
		
		if (bcClass.hasFunctionTypes())
		{
			writeFunctionTypes(bcClass);
		}
		
		if (isInterface)
		{
			src.writelnf("public interface %s", className);
			writeBlockOpen(src);
			writeInterfaceFunctions(bcClass);
			writeBlockClose(src);
		}
		else
		{
			if (bcClass.isFinal())
			{
				src.writef("public sealed class %s", className);
			}
			else
			{
				src.writef("public class %s", className);
			}
			
			boolean hasExtendsType = bcClass.hasExtendsType();
			boolean hasInterfaces = bcClass.hasInterfaces();
			
			if (hasExtendsType || hasInterfaces)
			{
				src.write(" : ");
			}
			
			if (hasExtendsType)
			{
				src.write(type(bcClass.getExtendsType()));
				if (hasInterfaces)
				{
					src.write(", ");
				}
			}
			
			if (hasInterfaces)
			{
				List<BcTypeNode> interfaces = bcClass.getInterfaces();
				int interfaceIndex= 0;
				for (BcTypeNode bcInterface : interfaces) 
				{					
					String interfaceType = type(bcInterface);
					src.write(++interfaceIndex == interfaces.size() ? interfaceType : (interfaceType + ", "));
				}
			}
			
			List<BcVariableDeclaration> bcInitializedFields = collectFieldsWithInitializer(bcClass);
			needFieldsInitializer = bcInitializedFields.size() > 0;
			
			src.writeln();
			writeBlockOpen(src);
			
			writeFields(bcClass);
			if (needFieldsInitializer)
			{
				writeFieldsInitializer(bcClass, bcInitializedFields);
			}
			writeFunctions(bcClass);
			
			writeBlockClose(src);
		}		
		
		writeBlockClose(src);
		
		writeDestToFile(src, outputFile);
	}

	private void writeFunctionTypes(BcClassDefinitionNode bcClass) 
	{
		List<BcFunctionTypeNode> functionTypes = bcClass.getFunctionTypes();
		for (BcFunctionTypeNode funcType : functionTypes) 
		{
			writeFunctionType(bcClass, funcType);
		}
	}

	private void writeFunctionType(BcClassDefinitionNode bcClass, BcFunctionTypeNode funcType) 
	{
		String type = funcType.hasReturnType() ? type(funcType.getReturnType()) : "void";
		String name = codeHelper.identifier(funcType.getName());			
		
		src.writelnf("public delegate %s %s(%s);", type, type(name), paramsString(funcType.getParams()));
	}

	private void writeFields(BcClassDefinitionNode bcClass)
	{
		List<BcVariableDeclaration> fields = bcClass.getFields();
		
		for (BcVariableDeclaration bcField : fields)
		{
			String type = type(bcField.getType());
			String name = codeHelper.identifier(bcField.getIdentifier());
						
			src.write(bcField.getVisiblity() + " ");
			
			if (bcField.isConst())
			{
				if (canBeClass(bcField.getType()))
				{
					src.write("static ");
				}
				else
				{
					src.write("const ");
				}
			}
			else if (bcField.isStatic())
			{
				src.write("static ");
			}			
			
			src.writef("%s %s", type, name);
			if (bcField.hasInitializer() && isSafeInitialized(bcClass, bcField))
			{
				src.writef(" = %s", bcField.getInitializer());
			}
			src.writeln(";");
		}
	}
	
	private void writeFieldsInitializer(BcClassDefinitionNode bcClass, List<BcVariableDeclaration> bcFields) 
	{
		src.writelnf("private void %s()", internalFieldInitializer);
		writeBlockOpen(src);
		
		for (BcVariableDeclaration bcVar : bcFields) 
		{
			String name = codeHelper.identifier(bcVar.getIdentifier());
			src.writelnf("%s = %s;", name, bcVar.getInitializer());
		}
		
		writeBlockClose(src);
	}
	
	private void writeFunctions(BcClassDefinitionNode bcClass)
	{
		List<BcFunctionDeclaration> functions = bcClass.getFunctions();
		for (BcFunctionDeclaration bcFunc : functions)
		{
			src.write(bcFunc.getVisiblity() + " ");
			if (bcFunc.isConstructor())
			{
				src.write(getClassName(bcClass));
			}			
			else
			{
				if (bcFunc.isStatic())
				{
					src.write("static ");
				}
				else if (bcFunc.isOverride())
				{
					src.write("override ");
				}
				else if (!bcFunc.isPrivate() && !bcClass.isFinal())
				{
					src.write("virtual ");
				}
				
				String type = bcFunc.hasReturnType() ? type(bcFunc.getReturnType()) : "void";
				String name = codeHelper.identifier(bcFunc.getName());			
				
				if (bcFunc.isGetter())
				{
					name = codeHelper.getter(name);
				}
				else if (bcFunc.isSetter())
				{
					name = codeHelper.setter(name);
				}
				src.writef("%s %s", type, name);
			}
			
			src.writelnf("(%s)", paramsString(bcFunc.getParams()));
			
			ListWriteDestination body = bcFunc.getBody();
			if (bcFunc.isConstructor())
			{
				writeConstructorBody(body);
			}
			else
			{
				src.writeln(body);
			}
		}
	}

	private String paramsString(List<BcFuncParam> params) 
	{
		ListWriteDestination paramsDest = new ListWriteDestination();
		int paramIndex = 0;
		for (BcFuncParam bcParam : params)
		{
			String paramType = type(bcParam.getType());
			String paramName = codeHelper.identifier(bcParam.getIdentifier());
			paramsDest.writef("%s %s", paramType, paramName);
			if (++paramIndex < params.size())
			{
				paramsDest.write(", ");
			}
		}
		
		return paramsDest.toString();
	}

	private void writeConstructorBody(ListWriteDestination body) 
	{
		List<String> lines = body.getLines();
		String firstLine = lines.get(1).trim();
		if (firstLine.startsWith(codeHelper.thisCallMarker))
		{
			firstLine = firstLine.replace(codeHelper.thisCallMarker, "this");
			if (firstLine.endsWith(";"))
			{
				firstLine = firstLine.substring(0, firstLine.length() - 1);
			}
			
			src.writeln(" : " + firstLine);
			lines.remove(1);
		}
		else if (firstLine.startsWith(codeHelper.superCallMarker))
		{
			firstLine = firstLine.replace(codeHelper.superCallMarker, "base");
			if (firstLine.endsWith(";"))
			{
				firstLine = firstLine.substring(0, firstLine.length() - 1);
			}
			
			src.writeln(" : " + firstLine);
			lines.remove(1);
		}
		
		if (needFieldsInitializer)
		{
			lines.add(1, String.format("\t%s();", internalFieldInitializer));
		}
		
		src.writeln(new ListWriteDestination(lines));
	}

}
