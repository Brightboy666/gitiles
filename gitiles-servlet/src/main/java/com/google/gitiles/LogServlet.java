// Copyright 2012 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gitiles;

import static com.google.common.base.Preconditions.checkNotNull;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.gson.reflect.TypeToken;

import org.eclipse.jgit.diff.DiffConfig;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.RevWalkException;
import org.eclipse.jgit.http.server.ServletUtils;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.FollowFilter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** Serves an HTML page with a shortlog for commits and paths. */
public class LogServlet extends BaseServlet {
  private static final long serialVersionUID = 1L;
  private static final Logger log = LoggerFactory.getLogger(LogServlet.class);

  static final String START_PARAM = "s";
  private static final int LIMIT = 100;

  private final Linkifier linkifier;

  public LogServlet(Renderer renderer, Linkifier linkifier) {
    super(renderer);
    this.linkifier = checkNotNull(linkifier, "linkifier");
  }

  @Override
  protected void doGetHtml(HttpServletRequest req, HttpServletResponse res) throws IOException {
    Repository repo = ServletUtils.getRepository(req);
    GitilesView view = getView(req, repo);
    Paginator paginator = newPaginator(repo, view);
    if (paginator == null) {
      res.setStatus(SC_NOT_FOUND);
      return;
    }

    try {
      Map<String, Object> data = new LogSoyData(req, view).toSoyData(paginator, null);

      if (!view.getRevision().nameIsId()) {
        List<Map<String, Object>> tags = Lists.newArrayListWithExpectedSize(1);
        for (RevObject o : RevisionServlet.listObjects(paginator.getWalk(), view.getRevision())) {
          if (o instanceof RevTag) {
            tags.add(new TagSoyData(linkifier, req).toSoyData((RevTag) o));
          }
        }
        if (!tags.isEmpty()) {
          data.put("tags", tags);
        }
      }

      String title = "Log - ";
      if (view.getOldRevision() != Revision.NULL) {
        title += view.getRevisionRange();
      } else {
        title += view.getRevision().getName();
      }

      data.put("title", title);

      renderHtml(req, res, "gitiles.logDetail", data);
    } catch (RevWalkException e) {
      log.warn("Error in rev walk", e);
      res.setStatus(SC_INTERNAL_SERVER_ERROR);
      return;
    } finally {
      paginator.getWalk().release();
    }
  }

  @Override
  protected void doGetJson(HttpServletRequest req, HttpServletResponse res) throws IOException {
    Repository repo = ServletUtils.getRepository(req);
    GitilesView view = getView(req, repo);
    Paginator paginator = newPaginator(repo, view);
    if (paginator == null) {
      res.setStatus(SC_NOT_FOUND);
      return;
    }

    try {
      Map<String, Object> result = Maps.newLinkedHashMap();
      List<CommitJsonData.Commit> entries = Lists.newArrayListWithCapacity(paginator.getLimit());
      for (RevCommit c : paginator) {
        paginator.getWalk().parseBody(c);
        entries.add(CommitJsonData.toJsonData(c));
      }
      result.put("log", entries);
      if (paginator.getPreviousStart() != null) {
        result.put("previous", paginator.getPreviousStart().name());
      }
      if (paginator.getNextStart() != null) {
        result.put("next", paginator.getNextStart().name());
      }
      renderJson(req, res, result, new TypeToken<Map<String, Object>>() {}.getType());
    } finally {
      paginator.getWalk().release();
    }
  }

  private static GitilesView getView(HttpServletRequest req, Repository repo) throws IOException {
    GitilesView view = ViewFilter.getView(req);
    if (view.getRevision() != Revision.NULL) {
      return view;
    }
    Ref headRef = repo.getRef(Constants.HEAD);
    if (headRef == null) {
      return null;
    }
    RevWalk walk = new RevWalk(repo);
    try {
      return GitilesView.log()
        .copyFrom(view)
        .setRevision(Revision.peel(Constants.HEAD, walk.parseAny(headRef.getObjectId()), walk))
        .build();
    } finally {
      walk.release();
    }
  }

  private static Optional<ObjectId> getStart(ListMultimap<String, String> params,
      ObjectReader reader) throws IOException {
    List<String> values = params.get(START_PARAM);
    switch (values.size()) {
      case 0:
        return Optional.absent();
      case 1:
        Collection<ObjectId> ids = reader.resolve(AbbreviatedObjectId.fromString(values.get(0)));
        if (ids.size() != 1) {
          return null;
        }
        return Optional.of(Iterables.getOnlyElement(ids));
      default:
        return null;
    }
  }

  private static RevWalk newWalk(Repository repo, GitilesView view)
      throws MissingObjectException, IncorrectObjectTypeException, IOException {
    RevWalk walk = new RevWalk(repo);
    walk.markStart(walk.parseCommit(view.getRevision().getId()));
    if (view.getOldRevision() != Revision.NULL) {
      walk.markUninteresting(walk.parseCommit(view.getOldRevision().getId()));
    }
    if (!Strings.isNullOrEmpty(view.getPathPart())) {
      walk.setTreeFilter(FollowFilter.create(
        view.getPathPart(),
        repo.getConfig().get(DiffConfig.KEY)));
    }
    return walk;
  }

  private static Paginator newPaginator(Repository repo, GitilesView view) throws IOException {
    if (view == null) {
      return null;
    }

    RevWalk walk = null;
    try {
      walk = newWalk(repo, view);
    } catch (IncorrectObjectTypeException e) {
      return null;
    }

    Optional<ObjectId> start;
    try {
      start = getStart(view.getParameters(), walk.getObjectReader());
    } catch (IOException e) {
      walk.release();
      throw e;
    }
    if (start == null) {
      return null;
    }
    return new Paginator(walk, LIMIT, start.orNull());
  }
}
