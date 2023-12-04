package com.dotcms.plugin.rest;

import com.dotcms.api.web.HttpServletRequestThreadLocal;
import com.dotcms.concurrent.DotConcurrentFactory;
import com.dotcms.concurrent.DotSubmitter;
import com.dotcms.rest.EmptyHttpResponse;
import com.dotcms.rest.WebResource;
import com.dotcms.rest.api.v1.DotObjectMapperProvider;
import com.dotcms.rest.api.v1.authentication.RequestUtil;
import com.dotcms.rest.api.v1.authentication.ResponseUtil;
import com.dotcms.util.CollectionsUtils;
import com.dotcms.util.DotPreconditions;
import com.dotmarketing.business.APILocator;
import com.dotmarketing.portlets.contentlet.business.ContentletAPI;
import com.dotmarketing.portlets.contentlet.model.Contentlet;
import com.dotmarketing.util.Config;
import com.dotmarketing.util.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liferay.portal.model.User;
import com.liferay.util.HttpHeaders;
import com.liferay.util.StringPool;
import org.apache.commons.lang.time.StopWatch;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

@Path("/maintenance")
public class CMSMaintenanceResource {

    private final WebResource webResource = new WebResource();

	@POST
	public Response doDelete(@Context final HttpServletRequest request,
						 @Context final HttpServletResponse response,
					   final DeleteContentsForm deleteContentsForm)  {

		final User user = new WebResource.InitBuilder(this.webResource).rejectWhenNoUser(true)
				.requestAndResponse(request, response)
				.requiredPortlet("maintenance")
				.requiredBackendUser(true).init().getUser();
		if (!user.isAdmin()) {

			Logger.debug(this.getClass().getName(), ()-> "User should be admin to delete contents");
			throw new IllegalArgumentException("User should be admin to delete contents");
		}

		DotPreconditions.notNull(deleteContentsForm,"Expected Request body was empty.");
		Logger.debug(this, ()-> "deleteContentsForm: " + deleteContentsForm.getContentletIds());
		return Response.ok(new CMSMaintenanceResource.MultipleContentletStreamingOutput(
				deleteContentsForm.getContentletIds(), user, request))
				.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON).build();
	}

	private class MultipleContentletStreamingOutput implements StreamingOutput {

		private final List<String> contentletIds;
		private final User user;
		private final HttpServletRequest request;

		private MultipleContentletStreamingOutput(final List<String> contentletIds, final User user, final HttpServletRequest request) {

			this.contentletIds = contentletIds;
			this.user          = user;
			this.request       = request;
		}

		@Override
		public void write(final OutputStream output) throws IOException, WebApplicationException {

			final ObjectMapper objectMapper = DotObjectMapperProvider.getInstance().getDefaultObjectMapper();
			CMSMaintenanceResource.this.deleteMultipleContentlets(request, this.contentletIds, this.user, output, objectMapper);
		}
	}

	private void deleteMultipleContentlets(
			final HttpServletRequest request,
			final List<String> contentletIds,
			final User user,
			final OutputStream outputStream,
			final ObjectMapper objectMapper) {

		final DotSubmitter dotSubmitter = DotConcurrentFactory.getInstance().getSubmitter("DELETE_CONTENTS_SUBMITTER",
				new DotConcurrentFactory.SubmitterConfigBuilder().poolSize(2).maxPoolSize(5)
						.queueCapacity(Config.getIntProperty("DELETE_CONTENTS_SUBMITTER_QUEUE",1000)).build());
		final CompletionService<Map<String, String>> completionService = new ExecutorCompletionService<>(dotSubmitter);
		final List<Future<Map<String, String>>> futures = new ArrayList<>();
		final HttpServletRequest statelessRequest = RequestUtil.INSTANCE.createStatelessRequest(request);
		final ContentletAPI contentletAPI = APILocator.getContentletAPI();
		final AtomicInteger contentletsDeleted = new AtomicInteger(0);

		for (final String contentInode : contentletIds) {

			// this triggers the save
			final Future<Map<String, String>> future = completionService.submit(() -> {

				HttpServletRequestThreadLocal.INSTANCE.setRequest(statelessRequest);
				final Map<String, String> resultMap = new HashMap<>();
				final List<String> conditionletWithErrors = new ArrayList<>();

				try {

					final  List<Contentlet> contentlets = contentletAPI.getSiblings(contentInode);
					for (final Contentlet contentlet : contentlets) {
						// we do not want to run a workflow.
						contentlet.setProperty(Contentlet.DISABLE_WORKFLOW, true);

						if (!contentletAPI.destroy(contentlet, user, true)) {
							resultMap.put(contentlet.getInode(), "Could not delete the contentlet: " + contentlet.getInode());
							conditionletWithErrors.add(contentlet.getIdentifier());
						} else {
							contentletsDeleted.incrementAndGet();
						}
					}
				} catch (Exception e) {

					final String msg = "Error deleting contentlet: " + contentInode + ", msg: " + e.getMessage();
					Logger.error(this, msg, e);
					resultMap.put(contentInode, msg);
				}

				return resultMap;
			});

			futures.add(future);
		}

		printResponseEntityViewResult(outputStream, objectMapper,
				completionService, futures, contentletsDeleted);
	}

	private void printResponseEntityViewResult(final OutputStream outputStream,
											   final ObjectMapper objectMapper,
											   final CompletionService<Map<String, String>> completionService,
											   final List<Future<Map<String, String>>> futures,
											   final AtomicInteger contentletsDeleted) {

		try {

			ResponseUtil.beginWrapResponseEntityView(outputStream, true);
			ResponseUtil.beginWrapProperty(outputStream, "results", false);
			outputStream.write(StringPool.OPEN_BRACKET.getBytes(StandardCharsets.UTF_8));
			final StopWatch stopWatch = new StopWatch();
			stopWatch.start();
			final Map<String, String> resultMapFails = new HashMap<>();
			// now recover the N results
			for (int i = 0; i < futures.size(); i++) {

				try {

					Logger.info(this, "Recovering the result " + (i + 1) + " of " + futures.size());
					final Map<String, String> resultMap = completionService.take().get();
					resultMapFails.putAll(resultMap);
				} catch (InterruptedException | ExecutionException e) {

					Logger.error(this, e.getMessage(), e);
				}
			}
			stopWatch.stop();

			outputStream.write(StringPool.CLOSE_BRACKET.getBytes(StandardCharsets.UTF_8));
			outputStream.write(StringPool.COMMA.getBytes(StandardCharsets.UTF_8));

			ResponseUtil.wrapProperty(outputStream, "summary",
					objectMapper.writeValueAsString(CollectionsUtils.map("time", stopWatch.getTime(),
							"affected", futures.size(),
							"successCount", contentletsDeleted.get(),
							"fails", resultMapFails)));
			outputStream.write(StringPool.COMMA.getBytes(StandardCharsets.UTF_8));

			ResponseUtil.endWrapResponseEntityView(outputStream, true);
		} catch (IOException e) {

			Logger.error(this, e.getMessage(), e);
		}
	}
}
