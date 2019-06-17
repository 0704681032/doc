spring源代码学习

```java
//很好的一个类 有parser 还有 函数式编程组合 (and or not eq) 还要学习其设计

/**
 * Internal parser used by {@link Profiles#of}.
 *
 * @author Phillip Webb
 * @since 5.1
 */
final class ProfilesParser {

	private ProfilesParser() {
	}


	static Profiles parse(String... expressions) {
		Assert.notEmpty(expressions, "Must specify at least one profile");
		Profiles[] parsed = new Profiles[expressions.length];
		for (int i = 0; i < expressions.length; i++) {
			parsed[i] = parseExpression(expressions[i]);
		}
		return new ParsedProfiles(expressions, parsed);
	}

	private static Profiles parseExpression(String expression) {
		Assert.hasText(expression, () -> "Invalid profile expression [" + expression + "]: must contain text");
		StringTokenizer tokens = new StringTokenizer(expression, "()&|!", true);
		return parseTokens(expression, tokens);
	}

	private static Profiles parseTokens(String expression, StringTokenizer tokens) {
		return parseTokens(expression, tokens, Context.NONE);
	}
	private static Profiles parseTokens(String expression, StringTokenizer tokens, Context context) {
		List<Profiles> elements = new ArrayList<>();
		Operator operator = null;
		while (tokens.hasMoreTokens()) {
			String token = tokens.nextToken().trim();
			if (token.isEmpty()) {
				continue;
			}
			switch (token) {
				case "(":
					Profiles contents = parseTokens(expression, tokens, Context.BRACKET);
					if (context == Context.INVERT) {
						return contents;
					}
					elements.add(contents);
					break;
				case "&":
					assertWellFormed(expression, operator == null || operator == Operator.AND);
					operator = Operator.AND;
					break;
				case "|":
					assertWellFormed(expression, operator == null || operator == Operator.OR);
					operator = Operator.OR;
					break;
				case "!":
					elements.add(not(parseTokens(expression, tokens, Context.INVERT)));
					break;
				case ")":
					Profiles merged = merge(expression, elements, operator);
					if (context == Context.BRACKET) {
						return merged;
					}
					elements.clear();
					elements.add(merged);
					operator = null;
					break;
				default:
					Profiles value = equals(token);
					if (context == Context.INVERT) {
						return value;
					}
					elements.add(value);
			}
		}
		return merge(expression, elements, operator);
	}

	private static Profiles merge(String expression, List<Profiles> elements, @Nullable Operator operator) {
		assertWellFormed(expression, !elements.isEmpty());
		if (elements.size() == 1) {
			return elements.get(0);
		}
		Profiles[] profiles = elements.toArray(new Profiles[0]);
		return (operator == Operator.AND ? and(profiles) : or(profiles));
	}

	private static void assertWellFormed(String expression, boolean wellFormed) {
		Assert.isTrue(wellFormed, () -> "Malformed profile expression [" + expression + "]");
	}

	private static Profiles or(Profiles... profiles) {
		return activeProfile -> Arrays.stream(profiles).anyMatch(isMatch(activeProfile));
	}

	private static Profiles and(Profiles... profiles) {
		return activeProfile -> Arrays.stream(profiles).allMatch(isMatch(activeProfile));
	}

	private static Profiles not(Profiles profiles) {
		return activeProfile -> !profiles.matches(activeProfile);
	}

	private static Profiles equals(String profile) {
		return activeProfile -> activeProfile.test(profile);
	}

	private static Predicate<Profiles> isMatch(Predicate<String> activeProfile) {
		return profiles -> profiles.matches(activeProfile);
	}


	private enum Operator {AND, OR}


	private enum Context {NONE, INVERT, BRACKET}


	private static class ParsedProfiles implements Profiles {

		private final String[] expressions;

		private final Profiles[] parsed;

		ParsedProfiles(String[] expressions, Profiles[] parsed) {
			this.expressions = expressions;
			this.parsed = parsed;
		}

		@Override
		public boolean matches(Predicate<String> activeProfiles) {
			for (Profiles candidate : this.parsed) {
				if (candidate.matches(activeProfiles)) {
					return true;
				}
			}
			return false;
		}

		@Override
		public String toString() {
			return StringUtils.arrayToDelimitedString(this.expressions, " or ");
		}
	}

}

```

```java
@Override
	protected final ClientHttpResponse executeInternal(HttpHeaders headers, byte[] bufferedOutput) throws IOException {
		InterceptingRequestExecution requestExecution = new InterceptingRequestExecution();
		return requestExecution.execute(this, bufferedOutput);
	}


	private class InterceptingRequestExecution implements ClientHttpRequestExecution {

		private final Iterator<ClientHttpRequestInterceptor> iterator;

		public InterceptingRequestExecution() {
			this.iterator = interceptors.iterator();
		}

		@Override
		public ClientHttpResponse execute(HttpRequest request, byte[] body) throws IOException {
			if (this.iterator.hasNext()) {
				ClientHttpRequestInterceptor nextInterceptor = this.iterator.next();
				return nextInterceptor.intercept(request, body, this);
			}
			else {
				HttpMethod method = request.getMethod();
				Assert.state(method != null, "No standard HTTP method");
				ClientHttpRequest delegate = requestFactory.createRequest(request.getURI(), method);
				request.getHeaders().forEach((key, value) -> delegate.getHeaders().addAll(key, value));
				if (body.length > 0) {
					if (delegate instanceof StreamingHttpOutputMessage) {
						StreamingHttpOutputMessage streamingOutputMessage = (StreamingHttpOutputMessage) delegate;
						streamingOutputMessage.setBody(outputStream -> StreamUtils.copy(body, outputStream));
					}
					else {
						StreamUtils.copy(body, delegate.getBody());
					}
				}
				return delegate.execute();
			}
		}
	}
```



```java
//检测应用程序的主类 SpringApplication
private Class<?> deduceMainApplicationClass() {
		try {
			StackTraceElement[] stackTrace = new RuntimeException().getStackTrace();
			for (StackTraceElement stackTraceElement : stackTrace) {
				if ("main".equals(stackTraceElement.getMethodName())) {
					return Class.forName(stackTraceElement.getClassName());
				}
			}
		}
		catch (ClassNotFoundException ex) {
			// Swallow and continue
		}
		return null;
	}
```



```java
public class DeferredResultMethodReturnValueHandler implements HandlerMethodReturnValueHandler {

	@Override
	public boolean supportsReturnType(MethodParameter returnType) {
		Class<?> type = returnType.getParameterType();
		return (DeferredResult.class.isAssignableFrom(type) ||
				ListenableFuture.class.isAssignableFrom(type) ||
				CompletionStage.class.isAssignableFrom(type));
	}

	@Override
	public void handleReturnValue(@Nullable Object returnValue, MethodParameter returnType,
			ModelAndViewContainer mavContainer, NativeWebRequest webRequest) throws Exception {

		if (returnValue == null) {
			mavContainer.setRequestHandled(true);
			return;
		}

		DeferredResult<?> result;

		if (returnValue instanceof DeferredResult) {
			result = (DeferredResult<?>) returnValue;
		}
		else if (returnValue instanceof ListenableFuture) {
			result = adaptListenableFuture((ListenableFuture<?>) returnValue);
		}
		else if (returnValue instanceof CompletionStage) {
			result = adaptCompletionStage((CompletionStage<?>) returnValue);
		}
		else {
			// Should not happen...
			throw new IllegalStateException("Unexpected return value type: " + returnValue);
		}

		WebAsyncUtils.getAsyncManager(webRequest).startDeferredResultProcessing(result, mavContainer);
	}

	private DeferredResult<Object> adaptListenableFuture(ListenableFuture<?> future) {
		DeferredResult<Object> result = new DeferredResult<>();
		future.addCallback(new ListenableFutureCallback<Object>() {
			@Override
			public void onSuccess(@Nullable Object value) {
				result.setResult(value);
			}
			@Override
			public void onFailure(Throwable ex) {
				result.setErrorResult(ex);
			}
		});
		return result;
	}

	private DeferredResult<Object> adaptCompletionStage(CompletionStage<?> future) {
		DeferredResult<Object> result = new DeferredResult<>();
		future.handle((BiFunction<Object, Throwable, Object>) (value, ex) -> {
			if (ex != null) {
				if (ex instanceof CompletionException && ex.getCause() != null) {
					ex = ex.getCause();
				}
				result.setErrorResult(ex);
			}
			else {
				result.setResult(value);
			}
			return null;
		});
		return result;
	}

}

```



```java
/*
 * Copyright 2017 Alibaba Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.aliyuncs.http.clients;

import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.conn.HttpClientConnectionManager;

public class ApacheIdleConnectionCleaner extends Thread {

    private static final Log LOG = LogFactory.getLog(ApacheIdleConnectionCleaner.class);
    private static final int PERIOD_SEC = 60;

    private static volatile ApacheIdleConnectionCleaner instance;
    private static final Map<HttpClientConnectionManager, Long> connMgrMap = new ConcurrentHashMap<HttpClientConnectionManager, Long>();

    private volatile boolean isShuttingDown;

    private ApacheIdleConnectionCleaner() {
        super("sdk-apache-idle-connection-cleaner");
        setDaemon(true);
    }

    public static void registerConnectionManager(HttpClientConnectionManager connMgr, Long idleTimeMills){
        if (instance == null) {
            synchronized (ApacheIdleConnectionCleaner.class) {
                if (instance == null) {
                    instance = new ApacheIdleConnectionCleaner();
                    instance.start();
                }
            }
        }
        connMgrMap.put(connMgr, idleTimeMills);
    }

    public static void removeConnectionManager(HttpClientConnectionManager connectionManager) {
        connMgrMap.remove(connectionManager);
        if (connMgrMap.isEmpty()) {
            shutdown();
        }
    }

    public static void shutdown(){
        if (instance != null) {
            instance.isShuttingDown = true;
            instance.interrupt();
            connMgrMap.clear();
            instance = null;
        }
    }

    @Override
    public void run() {
        while (true) {
            if (isShuttingDown) {
                LOG.debug("Shutting down.");
                return;
            }
            try {
                Thread.sleep(PERIOD_SEC * 1000);

                for (Entry<HttpClientConnectionManager, Long> entry : connMgrMap.entrySet()) {
                    try {
                        entry.getKey().closeIdleConnections(entry.getValue(), TimeUnit.MILLISECONDS);
                    } catch (Exception t) {
                        LOG.warn("close idle connections failed", t);
                    }
                }
            } catch (InterruptedException e){
                LOG.debug("interrupted.", e);
            } catch (Throwable t) {
                LOG.warn("fatal error", t);
            }
        }
    }

}
```



```java
public class ApacheHttpClient extends IHttpClient {

    protected static final String CONTENT_TYPE = "Content-Type";
    protected static final String ACCEPT_ENCODING = "Accept-Encoding";

    private static final String EXT_PARAM_KEY_BUILDER = "apache.httpclient.builder";
    private static final int DEFAULT_THREAD_KEEP_ALIVE_TIME = 60;

    private ExecutorService executorService;
    private CloseableHttpClient httpClient;
    private PoolingHttpClientConnectionManager connectionManager;

    public ApacheHttpClient(HttpClientConfig config) throws ClientException {
        super(config);
    }

    @Override
    protected void init(final HttpClientConfig config) throws ClientException {
        HttpClientBuilder builder;
        if (config.containsExtParam(EXT_PARAM_KEY_BUILDER)) {
            builder = (HttpClientBuilder)config.getExtParam(EXT_PARAM_KEY_BUILDER);
        } else {
            builder = HttpClientBuilder.create();
        }

        // default request config
        RequestConfig defaultConfig = RequestConfig.custom()
            .setConnectTimeout((int)config.getConnectionTimeoutMillis())
            .setSocketTimeout((int)config.getReadTimeoutMillis())
            .setConnectionRequestTimeout((int)config.getWriteTimeoutMillis())
            .build();
        builder.setDefaultRequestConfig(defaultConfig);

        // https
        RegistryBuilder<ConnectionSocketFactory> socketFactoryRegistryBuilder = RegistryBuilder.create();
        socketFactoryRegistryBuilder.register("http", new PlainConnectionSocketFactory());
        if (config.isIgnoreSSLCerts() || X509TrustAll.isIgnoreSSLCerts()) {
            try
            {
                SSLContext sslContext = new SSLContextBuilder().loadTrustMaterial(null, new TrustStrategy()
                {
                    // trust all
                    @Override
                    public boolean isTrusted(X509Certificate[] chain, String authType) throws CertificateException
                    {
                        return true;
                    }
                }).build();

                SSLConnectionSocketFactory connectionFactory = new SSLConnectionSocketFactory(sslContext, NoopHostnameVerifier.INSTANCE);

                socketFactoryRegistryBuilder.register("https", connectionFactory);

            } catch(Exception e) {
                throw new ClientException("SDK.InitFailed", "Init https with SSL certs ignore failed", e);
            }
        } else {
            if (config.getSslSocketFactory() != null) {
                SSLConnectionSocketFactory sslConnectionSocketFactory = new SSLConnectionSocketFactory(config.getSslSocketFactory(),
                    config.getHostnameVerifier());
                socketFactoryRegistryBuilder.register("https", sslConnectionSocketFactory);
            }else if (config.getKeyManagers() != null || config.getX509TrustManagers() != null || config.getSecureRandom() != null) {
                try {
                    SSLContext sslContext = SSLContext.getInstance("TLS");
                    sslContext.init(config.getKeyManagers(), config.getX509TrustManagers(), config.getSecureRandom());
                    SSLConnectionSocketFactory sslConnectionSocketFactory = new SSLConnectionSocketFactory(sslContext);
                    socketFactoryRegistryBuilder.register("https", sslConnectionSocketFactory);
                } catch (NoSuchAlgorithmException e1) {
                    throw new SSLInitializationException(e1.getMessage(), e1);
                } catch (KeyManagementException e2) {
                    throw new SSLInitializationException(e2.getMessage(), e2);
                }
            }
        }

        // connPool
        connectionManager = new PoolingHttpClientConnectionManager(socketFactoryRegistryBuilder.build());
        connectionManager.setMaxTotal(config.getMaxRequests());
        connectionManager.setDefaultMaxPerRoute(config.getMaxRequestsPerHost());
        builder.setConnectionManager(connectionManager);
        ApacheIdleConnectionCleaner.registerConnectionManager(connectionManager, config.getMaxIdleTimeMillis());

        // async
        if (config.getExecutorService() == null) {
            executorService = new ThreadPoolExecutor(0, config.getMaxRequests(), DEFAULT_THREAD_KEEP_ALIVE_TIME, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(),
                new DefaultAsyncThreadFactory());
        } else {
            executorService = config.getExecutorService();
        }

        // keepAlive
        if (config.getKeepAliveDurationMillis() > 0) {
            builder.setKeepAliveStrategy(new ConnectionKeepAliveStrategy() {
                @Override
                public long getKeepAliveDuration(HttpResponse response, HttpContext context) {
                    long duration = DefaultConnectionKeepAliveStrategy.INSTANCE.getKeepAliveDuration(response, context);

                    if (duration > 0 && duration < config.getKeepAliveDurationMillis()) {
                        return duration;
                    } else {
                        return config.getKeepAliveDurationMillis();
                    }
                }
            });
        }

        httpClient = builder.build();
    }
}
```

