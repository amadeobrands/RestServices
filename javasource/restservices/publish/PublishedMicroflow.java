package restservices.publish;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.json.JSONObject;

import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.systemwideinterfaces.core.IDataType;
import com.mendix.systemwideinterfaces.core.IDataType.DataTypeEnum;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import communitycommons.XPath;

import restservices.RestServices;
import restservices.publish.RestServiceRequest.RequestContentType;
import restservices.publish.RestServiceRequest.ResponseType;
import restservices.util.JSONSchemaBuilder;
import restservices.util.JsonDeserializer;
import restservices.util.JsonSerializer;
import restservices.util.Utils;
import system.proxies.FileDocument;
import system.proxies.UserRole;

public class PublishedMicroflow {
	
	private String microflowname;
	private boolean hasArgument;
	private String argType;
	private boolean isReturnTypeString;
	private String returnType;
	private String argName;
	private String securityRole;
	private String description;

	public PublishedMicroflow(String microflowname, String securityRole, String description) throws CoreException{
		this.microflowname = microflowname;
		this.securityRole = securityRole;
		this.description = description;
		this.consistencyCheck();
		RestServices.registerPublishedMicroflow(this);
	}

	private void consistencyCheck() throws CoreException {
		if (!"*".equals(securityRole) && null == XPath.create(Core.createSystemContext(), UserRole.class).eq(UserRole.MemberNames.Name, securityRole).first())
			throw new IllegalArgumentException("Cannot publish microflow " + microflowname+ ", the security role should exists, or the microflow should be marked world readable by using role '*'");
		
		int argCount = Utils.getArgumentTypes(microflowname).size(); 
		
		if (argCount > 1)
			throw new IllegalArgumentException("Cannot publish microflow " + microflowname+ ", it should exist and have exactly zero or one argument");
		
		hasArgument = argCount == 1;
		if (hasArgument) {
			IDataType argtype = Utils.getFirstArgumentType(microflowname);
			if (!argtype.isMendixObject())
				throw new IllegalArgumentException("Cannot publish microflow " + microflowname+ ", it should have a single object as input argument");
			this.argType = argtype.getObjectType();
			this.argName = Utils.getArgumentTypes(microflowname).keySet().iterator().next();
		}
		
		if (Core.getMetaObject(argType).isPersistable() && !Core.isSubClassOf(FileDocument.entityName, argType))
			throw new IllegalArgumentException("Cannot publish microflow " + microflowname+ ", it should have a transient object of filedocument as input argument");
		
		IDataType returnTypeFromMF = Core.getReturnType(microflowname);
		this.isReturnTypeString = returnTypeFromMF.getType() == DataTypeEnum.String; 
		if (!isReturnTypeString) {
			if (!returnTypeFromMF.isMendixObject() && !returnTypeFromMF.isList())
				throw new IllegalArgumentException("Cannot publish microflow " + microflowname+ ", its return type should be a String, List or Object type");
			if (returnTypeFromMF.isMendixObject() || returnTypeFromMF.isList()){
				this.returnType = returnTypeFromMF.getObjectType();
				if (Core.getMetaObject(this.returnType).isPersistable()  && !Core.isSubClassOf(FileDocument.entityName, this.returnType))
					throw new IllegalArgumentException("Cannot publish microflow " + microflowname+ ", its return type should be a non-persistable object or a file document");
			}
		}
	}
	
	void execute(RestServiceRequest rsr) throws Exception {
		
		Map<String, Object> args = new HashMap<String, Object>();
		if (hasArgument) {
			IMendixObject argO = Core.instantiate(rsr.getContext(), argType);
			JSONObject data = null;
			
			if (rsr.getRequestContentType() == RequestContentType.JSON) { 
				String body = IOUtils.toString(rsr.request.getInputStream());
				data = new JSONObject(StringUtils.isEmpty(body) ? "{}" : body);
			}
			
			if (data == null)
				data = new JSONObject();
			
			RestServiceHandler.requestParamsToJsonMap(rsr, data);
			
			JsonDeserializer.readJsonDataIntoMendixObject(rsr.getContext(), data, argO, false);
			args.put(argName, argO);
		}
		
		Object result = Core.execute(rsr.getContext(), microflowname, args);
		if (result == null) {
			//write nothing
		}
		else if (this.isReturnTypeString) {
			rsr.write(String.valueOf(result));
		}
		else if (result instanceof List<?>) {
			rsr.datawriter.array();
			for(Object item : (List<?>)result)
				rsr.datawriter.value(JsonSerializer.writeMendixObjectToJson(rsr.getContext(), (IMendixObject) item));
			rsr.datawriter.endArray();
			
		}
		else if (result instanceof IMendixObject) {
			rsr.datawriter.value(JsonSerializer.writeMendixObjectToJson(rsr.getContext(), (IMendixObject) result));
		}
		else throw new IllegalStateException("Unexpected result from microflow " + microflowname + ": " + result.getClass().getName());
	}

	public String getName() {
		return microflowname.split("\\.")[1];
	}

	public String getRequiredRole() {
		return securityRole;
	}

	public void serveDescription(RestServiceRequest rsr) {
		rsr.startDoc();
		
		if (rsr.getResponseContentType() == ResponseType.HTML)
			rsr.write("<h1>Operation '").write(getName()).write("'</h1>");
		
		rsr.datawriter.object()
			.key("name").value(getName())
			.key("description").value(description)
			.key("url").value(RestServices.getServiceUrl(getName()))
			.key("arguments").value(JSONSchemaBuilder.build(Utils.getFirstArgumentType(microflowname)))
			.key("result").value(JSONSchemaBuilder.build(Core.getReturnType(microflowname)))
			.endObject();
		
		rsr.endDoc();
	}
}
