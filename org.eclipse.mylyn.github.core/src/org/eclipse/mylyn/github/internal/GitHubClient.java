/*******************************************************************************
 *  Copyright (c) 2011 GitHub Inc.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  Contributors:
 *    Kevin Sawicki (GitHub Inc.) - initial API and implementation
 *******************************************************************************/
package org.eclipse.mylyn.github.internal;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.Date;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.httpclient.Credentials;
import org.apache.commons.httpclient.HostConfiguration;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.HttpMethodBase;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.auth.BasicScheme;
import org.apache.commons.httpclient.methods.EntityEnclosingMethod;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.PutMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.commons.httpclient.protocol.Protocol;

/**
 * Client class for interacting with GitHub HTTP/JSON API.
 */
public class GitHubClient {

	private static final AuthScope ANY_SCOPE = new AuthScope(
			AuthScope.ANY_HOST, AuthScope.ANY_PORT);

	private HostConfiguration hostConfig;

	private HttpClient client = new HttpClient();

	private Gson gson = new GsonBuilder()
			.registerTypeAdapter(Date.class, new DateFormatter())
			.setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
			.create();

	private boolean sendCredentials = false;

	/**
	 * Create default client
	 */
	public GitHubClient() {
		this.hostConfig = new HostConfiguration();
		this.hostConfig.setHost(IGitHubConstants.HOST_API, -1,
				Protocol.getProtocol(IGitHubConstants.PROTOCOL_HTTPS));
	}

	/**
	 * Create client for configuration
	 * 
	 * @param configuration
	 */
	public GitHubClient(HostConfiguration configuration) {
		this.hostConfig = configuration;
	}

	/**
	 * Create standard post method
	 * 
	 * @param uri
	 * @return post
	 */
	protected PostMethod createPost(String uri) {
		PostMethod method = new PostMethod(uri);
		setMethodDefaults(method);
		return method;
	}

	/**
	 * Create standard post method
	 * 
	 * @param uri
	 * @return post
	 */
	protected PutMethod createPut(String uri) {
		PutMethod method = new PutMethod(uri);
		setMethodDefaults(method);
		return method;
	}

	/**
	 * Set method defaults
	 * 
	 * @param method
	 * @return method
	 */
	protected HttpMethod setMethodDefaults(HttpMethod method) {
		if (this.sendCredentials) {
			method.setDoAuthentication(true);
			method.getHostAuthState().setPreemptive();
			method.getHostAuthState().setAuthScheme(new BasicScheme());
		}
		return method;
	}

	/**
	 * Create get method
	 * 
	 * @param uri
	 * @return get method
	 */
	protected GetMethod createGet(String uri) {
		GetMethod method = new GetMethod(uri);
		method.setFollowRedirects(true);
		setMethodDefaults(method);
		return method;
	}

	/**
	 * Set credentials
	 * 
	 * @param user
	 * @param password
	 */
	public void setCredentials(String user, String password) {
		this.sendCredentials = user != null && password != null;
		Credentials credentials = null;
		if (this.sendCredentials)
			credentials = new UsernamePasswordCredentials(user, password);
		this.client.getState().setCredentials(ANY_SCOPE, credentials);
	}

	/**
	 * Parse json to specified type
	 * 
	 * @param <V>
	 * @param method
	 * @param type
	 * @return type
	 * @throws IOException
	 */
	protected <V> V parseJson(HttpMethodBase method, Type type)
			throws IOException {
		InputStream stream = method.getResponseBodyAsStream();
		if (stream == null)
			throw new JsonParseException("Empty body"); //$NON-NLS-1$
		InputStreamReader reader = new InputStreamReader(stream);
		return this.gson.fromJson(reader, type);
	}

	/**
	 * Get name value pairs for data map.
	 * 
	 * @param data
	 * @return name value pair array
	 */
	protected NameValuePair[] getPairs(Map<String, String> data) {
		NameValuePair[] pairs = new NameValuePair[data.size()];
		int i = 0;
		for (Entry<String, String> entry : data.entrySet()) {
			pairs[i] = new NameValuePair(entry.getKey(), entry.getValue());
			i++;
		}
		return pairs;
	}

	/**
	 * Get response from uri and bind to specified type
	 * 
	 * @param <V>
	 * @param uri
	 * @param type
	 * @return V
	 * @throws IOException
	 */
	public <V> V get(String uri, Type type) throws IOException {
		return get(uri, null, type);
	}

	/**
	 * Get response stream from uri. It is the responsibility of the calling
	 * method to close the returned stream.
	 * 
	 * @param uri
	 * @param params
	 * @return V
	 * @throws IOException
	 */
	public InputStream getStream(String uri, Map<String, String> params)
			throws IOException {
		GetMethod method = createGet(uri);
		if (params != null && !params.isEmpty())
			method.setQueryString(getPairs(params));

		try {
			int status = this.client.executeMethod(this.hostConfig, method);
			switch (status) {
			case 200:
				return method.getResponseBodyAsStream();
			case 400:
			case 401:
			case 403:
			case 404:
			case 500:
				RequestError error = parseJson(method, RequestError.class);
				throw new RequestException(error, status);
			default:
				throw new IOException(method.getStatusText());
			}
		} catch (JsonParseException jpe) {
			throw new IOException(jpe);
		}
	}

	/**
	 * Get response from uri and bind to specified type
	 * 
	 * @param <V>
	 * @param uri
	 * @param params
	 * @param type
	 * @return V
	 * @throws IOException
	 */
	public <V> V get(String uri, Map<String, String> params, Type type)
			throws IOException {
		GetMethod method = createGet(uri);
		if (params != null && !params.isEmpty())
			method.setQueryString(getPairs(params));

		try {
			int status = this.client.executeMethod(this.hostConfig, method);
			switch (status) {
			case 200:
				return parseJson(method, type);
			case 400:
			case 401:
			case 403:
			case 404:
			case 500:
				RequestError error = parseJson(method, RequestError.class);
				throw new RequestException(error, status);
			default:
				throw new IOException(method.getStatusText());
			}
		} catch (JsonParseException jpe) {
			throw new IOException(jpe);
		} finally {
			method.releaseConnection();
		}
	}

	/**
	 * Send json using specified method
	 * 
	 * @param <V>
	 * @param method
	 * @param params
	 * @param type
	 * @return resource
	 * @throws IOException
	 */
	protected <V> V sendJson(EntityEnclosingMethod method, Object params,
			Type type) throws IOException {
		if (params != null) {
			StringBuilder payload = new StringBuilder();
			this.gson.toJson(params, payload);
			method.setRequestEntity(new StringRequestEntity(payload.toString(),
					IGitHubConstants.CONTENT_TYPE_JSON,
					IGitHubConstants.CHARSET_UTF8));
		}

		try {
			int status = this.client.executeMethod(this.hostConfig, method);
			switch (status) {
			case 200:
			case 201:
				if (type != null)
					return parseJson(method, type);
			case 204:
				break;
			case 400:
			case 401:
			case 403:
			case 404:
			case 500:
				RequestError error = parseJson(method, RequestError.class);
				throw new RequestException(error, status);
			default:
				throw new IOException(method.getStatusText());
			}
		} finally {
			method.releaseConnection();
		}
		return null;
	}

	/**
	 * Post data to uri
	 * 
	 * @param <V>
	 * @param uri
	 * @param params
	 * @param type
	 * @return response
	 * @throws IOException
	 */
	public <V> V post(String uri, Object params, Type type) throws IOException {
		PostMethod method = createPost(uri);
		return sendJson(method, params, type);
	}

	/**
	 * Put data to uri
	 * 
	 * @param <V>
	 * @param uri
	 * @param params
	 * @param type
	 * @return response
	 * @throws IOException
	 */
	public <V> V put(String uri, Object params, Type type) throws IOException {
		PutMethod method = createPut(uri);
		return sendJson(method, params, type);
	}

}
