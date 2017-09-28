package org.visallo.vertexium.model.search;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Iterables;
import com.google.inject.Inject;
import org.json.JSONObject;
import org.vertexium.*;
import org.visallo.core.config.Configuration;
import org.visallo.core.exception.VisalloAccessDeniedException;
import org.visallo.core.model.properties.VisalloProperties;
import org.visallo.core.model.search.SearchProperties;
import org.visallo.core.model.search.SearchRepository;
import org.visallo.core.model.user.AuthorizationRepository;
import org.visallo.core.model.user.GraphAuthorizationRepository;
import org.visallo.core.model.user.PrivilegeRepository;
import org.visallo.core.model.user.UserRepository;
import org.visallo.core.security.VisalloVisibility;
import org.visallo.core.user.User;
import org.visallo.core.util.ClientApiConverter;
import org.visallo.web.clientapi.model.ClientApiSearch;
import org.visallo.web.clientapi.model.ClientApiSearchListResponse;
import org.visallo.web.clientapi.model.Privilege;

import java.util.List;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.visallo.core.util.StreamUtil.stream;

public class VertexiumSearchRepository extends SearchRepository {
    public static final String VISIBILITY_STRING = "search";
    public static final VisalloVisibility VISIBILITY = new VisalloVisibility(VISIBILITY_STRING);
    private static final String GLOBAL_SAVED_SEARCHES_ROOT_VERTEX_ID = "__visallo_globalSavedSearchesRoot";
    private final Graph graph;
    private final UserRepository userRepository;
    private final AuthorizationRepository authorizationRepository;
    private final PrivilegeRepository privilegeRepository;

    @Inject
    public VertexiumSearchRepository(
            Graph graph,
            UserRepository userRepository,
            Configuration configuration,
            GraphAuthorizationRepository graphAuthorizationRepository,
            AuthorizationRepository authorizationRepository,
            PrivilegeRepository privilegeRepository
    ) {
        super(configuration);
        this.graph = graph;
        this.userRepository = userRepository;
        this.authorizationRepository = authorizationRepository;
        this.privilegeRepository = privilegeRepository;
        graphAuthorizationRepository.addAuthorizationToGraph(VISIBILITY_STRING);
    }

    @Override
    public String saveSearch(
            String id,
            String name,
            String url,
            JSONObject searchParameters,
            User user
    ) {
        checkNotNull(user, "User is required");
        Authorizations authorizations = authorizationRepository.getGraphAuthorizations(
                user,
                VISIBILITY_STRING,
                UserRepository.VISIBILITY_STRING
        );

        if (graph.doesVertexExist(id, authorizations)) {
            // switching from global to private
            if (isSearchGlobal(id, authorizations)) {
                if (privilegeRepository.hasPrivilege(user, Privilege.SEARCH_SAVE_GLOBAL)) {
                    deleteSearch(id, user);
                } else {
                    throw new VisalloAccessDeniedException(
                            "User does not have the privilege to change a global search", user, id);
                }
            } else if (!isSearchPrivateToUser(id, user, authorizations)) {
                throw new VisalloAccessDeniedException("User does not own this this search", user, id);
            }
        }

        Vertex searchVertex = saveSearchVertex(id, name, url, searchParameters, authorizations);

        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        checkNotNull(userVertex, "Could not find user vertex with id " + user.getUserId());
        String edgeId = userVertex.getId() + "_" + SearchProperties.HAS_SAVED_SEARCH + "_" + searchVertex.getId();
        graph.addEdge(
                edgeId,
                userVertex,
                searchVertex,
                SearchProperties.HAS_SAVED_SEARCH,
                VISIBILITY.getVisibility(),
                authorizations
        );

        graph.flush();

        return searchVertex.getId();
    }

    @Override
    public void favoriteSearch(
            String id,
            User user
    ) {
        checkNotNull(user, "User is required");
        Authorizations authorizations = authorizationRepository.getGraphAuthorizations(
                user,
                VISIBILITY_STRING,
                UserRepository.VISIBILITY_STRING
        );

        Vertex searchVertex = getSavedSearchVertex(id, user);
        checkNotNull(searchVertex, "Could not find search vertex with id " + id);

        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        checkNotNull(userVertex, "Could not find user vertex with id " + user.getUserId());

        String edgeId = getFavoriteEdgeId(userVertex, searchVertex);
        if (!graph.doesEdgeExist(edgeId, authorizations)) {
            graph.addEdge(
                    edgeId,
                    userVertex,
                    searchVertex,
                    SearchProperties.HAS_FAVORITED,
                    VISIBILITY.getVisibility(),
                    authorizations
            );

            graph.flush();
        }
    }

    @Override
    public String saveGlobalSearch(
            String id,
            String name,
            String url,
            JSONObject searchParameters,
            User user
    ) {
        if (!privilegeRepository.hasPrivilege(user, Privilege.SEARCH_SAVE_GLOBAL)) {
            throw new VisalloAccessDeniedException(
                    "User does not have the privilege to save a global search", user, id);
        }

        Authorizations authorizations = authorizationRepository.getGraphAuthorizations(
                userRepository.getSystemUser(),
                VISIBILITY_STRING,
                UserRepository.VISIBILITY_STRING
        );

        // switching from private to global
        if (isSearchPrivateToUser(id, user, authorizations)) {
            deleteSearch(id, user);
        }

        Vertex searchVertex = saveSearchVertex(id, name, url, searchParameters, authorizations);

        String edgeId = String.format(
                "%s_%s_%s",
                GLOBAL_SAVED_SEARCHES_ROOT_VERTEX_ID, SearchProperties.HAS_SAVED_SEARCH, searchVertex.getId());
        graph.addEdge(
                edgeId,
                getGlobalSavedSearchesRootVertex(),
                searchVertex,
                SearchProperties.HAS_SAVED_SEARCH,
                VISIBILITY.getVisibility(),
                authorizations
        );

        graph.flush();

        return searchVertex.getId();
    }

    private Vertex saveSearchVertex(
            String id,
            String name,
            String url,
            JSONObject searchParameters,
            Authorizations authorizations
    ) {
        VertexBuilder searchVertexBuilder = graph.prepareVertex(id, VISIBILITY.getVisibility());
        VisalloProperties.CONCEPT_TYPE.setProperty(
                searchVertexBuilder,
                SearchProperties.CONCEPT_TYPE_SAVED_SEARCH,
                VISIBILITY.getVisibility()
        );
        SearchProperties.NAME.setProperty(searchVertexBuilder, name != null ? name : "", VISIBILITY.getVisibility());
        SearchProperties.URL.setProperty(searchVertexBuilder, url, VISIBILITY.getVisibility());
        SearchProperties.PARAMETERS.setProperty(searchVertexBuilder, searchParameters, VISIBILITY.getVisibility());
        return searchVertexBuilder.save(authorizations);
    }

    @Override
    public ClientApiSearchListResponse getAllSavedSearches(User user) {
        checkNotNull(user, "User is required");
        Authorizations authorizations = authorizationRepository.getGraphAuthorizations(
                user,
                VISIBILITY_STRING,
                UserRepository.VISIBILITY_STRING
        );

        ClientApiSearchListResponse result = new ClientApiSearchListResponse();
        Iterables.addAll(result.searches, getGlobalSavedSearches(user, authorizations));
        Iterables.addAll(result.searches, getUserSavedSearches(user, authorizations));
        return result;
    }

    @Override
    public ClientApiSearchListResponse getAllFavoriteSavedSearches(User user) {
        checkNotNull(user, "User is required");
        Authorizations authorizations = authorizationRepository.getGraphAuthorizations(
                user,
                VISIBILITY_STRING,
                UserRepository.VISIBILITY_STRING
        );

        ClientApiSearchListResponse result = new ClientApiSearchListResponse();
        Iterables.addAll(result.searches, getUserFavoritedSavedSearches(user, authorizations));
        return result;
    }

    @Override
    public ClientApiSearchListResponse getGlobalSavedSearches(User user) {
        checkNotNull(user, "User is required");
        Authorizations authorizations = authorizationRepository.getGraphAuthorizations(
                user,
                VISIBILITY_STRING,
                UserRepository.VISIBILITY_STRING
        );

        ClientApiSearchListResponse result = new ClientApiSearchListResponse();
        Iterables.addAll(result.searches, getGlobalSavedSearches(user, authorizations));
        return result;
    }

    @Override
    public ClientApiSearchListResponse getUserSavedSearches(User user) {
        checkNotNull(user, "User is required");
        Authorizations authorizations = authorizationRepository.getGraphAuthorizations(
                user,
                VISIBILITY_STRING,
                UserRepository.VISIBILITY_STRING
        );

        ClientApiSearchListResponse result = new ClientApiSearchListResponse();
        Iterables.addAll(result.searches, getUserSavedSearches(user, authorizations));
        return result;
    }

    private Iterable<ClientApiSearch> getUserSavedSearches(User user, Authorizations authorizations) {
        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        checkNotNull(userVertex, "Could not find user vertex with id " + user.getUserId());
        Iterable<Vertex> userSearchVertices = userVertex.getVertices(
                Direction.OUT,
                SearchProperties.HAS_SAVED_SEARCH,
                authorizations
        );
        List<String> userFavoritedSearchIds = getUserFavoritedSearchIds(userVertex, authorizations);
        return stream(userSearchVertices)
                .map(searchVertex -> {
                    boolean favorited = userFavoritedSearchIds.contains(searchVertex.getId());
                    return toClientApiSearch(searchVertex, favorited, ClientApiSearch.Scope.User);
                })
                .collect(Collectors.toList());
    }

    private List<String> getUserFavoritedSearchIds(Vertex userVertex, Authorizations authorizations) {
        Iterable<EdgeInfo> edgeInfos = userVertex.getEdgeInfos(Direction.OUT, SearchProperties.HAS_FAVORITED, authorizations);
        return stream(edgeInfos)
                .map(EdgeInfo::getVertexId)
                .collect(Collectors.toList());
    }

    private Iterable<ClientApiSearch> getUserFavoritedSavedSearches(User user, Authorizations authorizations) {
        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        checkNotNull(userVertex, "Could not find user vertex with id " + user.getUserId());

        ClientApiSearchListResponse result = new ClientApiSearchListResponse();
        Iterable<ClientApiSearch> allGlobalSavedSearches = getGlobalSavedSearches(user, authorizations);
        Iterable<ClientApiSearch> allUserSavedSearches = getUserSavedSearches(user, authorizations);
        Iterables.addAll(result.searches, allGlobalSavedSearches);
        Iterables.addAll(result.searches, allUserSavedSearches);

        return stream(result.searches)
                .filter(search -> search.favorited)
                .collect(Collectors.toList());
    }

    private Iterable<ClientApiSearch> getGlobalSavedSearches(User user, Authorizations authorizations) {
        Vertex globalSavedSearchesRootVertex = getGlobalSavedSearchesRootVertex();
        Iterable<Vertex> globalSearchVertices = globalSavedSearchesRootVertex.getVertices(
                Direction.OUT,
                SearchProperties.HAS_SAVED_SEARCH,
                authorizations
        );

        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        checkNotNull(userVertex, "Could not find user vertex with id " + user.getUserId());
        List<String> userFavoritedSearchIds = getUserFavoritedSearchIds(userVertex, authorizations);
        return stream(globalSearchVertices)
                .map(searchVertex -> {
                    boolean favorited = userFavoritedSearchIds.contains(searchVertex.getId());
                    return toClientApiSearch(searchVertex, favorited, ClientApiSearch.Scope.Global);
                })
                .collect(Collectors.toList());
    }

    private Vertex getGlobalSavedSearchesRootVertex() {
        Authorizations authorizations = authorizationRepository.getGraphAuthorizations(
                userRepository.getSystemUser(),
                VISIBILITY_STRING
        );
        Vertex globalSavedSearchesRootVertex = graph.getVertex(GLOBAL_SAVED_SEARCHES_ROOT_VERTEX_ID, authorizations);
        if (globalSavedSearchesRootVertex == null) {
            globalSavedSearchesRootVertex = graph.prepareVertex(
                    GLOBAL_SAVED_SEARCHES_ROOT_VERTEX_ID,
                    new Visibility(VISIBILITY_STRING)
            )
                    .save(authorizations);
            graph.flush();
        }
        return globalSavedSearchesRootVertex;
    }

    private static ClientApiSearch toClientApiSearch(Vertex searchVertex) {
        return toClientApiSearch(searchVertex, null);
    }

    public static ClientApiSearch toClientApiSearch(Vertex searchVertex, ClientApiSearch.Scope scope) {
        return toClientApiSearch(searchVertex, false, scope);
    }

    public static ClientApiSearch toClientApiSearch(Vertex searchVertex, boolean favorited, ClientApiSearch.Scope scope) {
        ClientApiSearch result = new ClientApiSearch();
        result.id = searchVertex.getId();
        result.name = SearchProperties.NAME.getPropertyValue(searchVertex);
        result.url = SearchProperties.URL.getPropertyValue(searchVertex);
        result.scope = scope;
        result.favorited = favorited;
        result.parameters = ClientApiConverter.toClientApiValue(SearchProperties.PARAMETERS.getPropertyValue(
                searchVertex));
        return result;
    }

    @Override
    public ClientApiSearch getSavedSearch(String id, User user) {
        return toClientApiSearch(getSavedSearchVertex(id, user));
    }

    @Override
    public void deleteSearch(final String id, User user) {
        checkNotNull(user, "User is required");
        Authorizations authorizations = authorizationRepository.getGraphAuthorizations(
                user,
                VISIBILITY_STRING,
                UserRepository.VISIBILITY_STRING
        );
        Vertex searchVertex = graph.getVertex(id, authorizations);
        checkNotNull(searchVertex, "Could not find search with id " + id);

        if (isSearchGlobal(id, authorizations)) {
            if (!privilegeRepository.hasPrivilege(user, Privilege.SEARCH_SAVE_GLOBAL)) {
                throw new VisalloAccessDeniedException(
                        "User does not have the privilege to delete a global search", user, id);
            }
        } else if (!isSearchPrivateToUser(id, user, authorizations)) {
            throw new VisalloAccessDeniedException("User does not own this this search", user, id);
        }

        graph.deleteVertex(searchVertex, authorizations);
        graph.flush();
    }

    @Override
    public void unfavoriteSearch(final String id, User user) {
        checkNotNull(user, "User is required");
        Authorizations authorizations = authorizationRepository.getGraphAuthorizations(
                user,
                VISIBILITY_STRING,
                UserRepository.VISIBILITY_STRING
        );

        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        checkNotNull(userVertex, "Could not find user vertex with id " + user.getUserId());

        Vertex searchVertex = graph.getVertex(id, authorizations);
        checkNotNull(searchVertex, "Could not find search with id " + id);

        String edgeId = getFavoriteEdgeId(userVertex, searchVertex);
        Edge favoritedEdge = graph.getEdge(edgeId, authorizations);
        checkNotNull(favoritedEdge, "Could not find favorited edge with id " + edgeId);

        graph.deleteEdge(favoritedEdge, authorizations);
        graph.flush();
    }

    @VisibleForTesting
    boolean isSearchGlobal(String id, Authorizations authorizations) {
        if (!graph.doesVertexExist(id, authorizations)) {
            return false;
        }
        Iterable<String> vertexIds = getGlobalSavedSearchesRootVertex().getVertexIds(
                Direction.OUT,
                SearchProperties.HAS_SAVED_SEARCH,
                authorizations
        );
        return stream(vertexIds).anyMatch(vertexId -> vertexId.equals(id));
    }

    @VisibleForTesting
    boolean isSearchPrivateToUser(String id, User user, Authorizations authorizations) {
        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        checkNotNull(userVertex, "Could not find user vertex with id " + user.getUserId());
        Iterable<String> vertexIds = userVertex.getVertexIds(
                Direction.OUT,
                SearchProperties.HAS_SAVED_SEARCH,
                authorizations
        );
        return stream(vertexIds).anyMatch(vertexId -> vertexId.equals(id));
    }

    private Vertex getSavedSearchVertex(String id, User user) {
        Authorizations authorizations = authorizationRepository.getGraphAuthorizations(
                user,
                VISIBILITY_STRING,
                UserRepository.VISIBILITY_STRING
        );
        Vertex searchVertex = graph.getVertex(id, authorizations);
        if (searchVertex == null) {
            return null;
        }
        return searchVertex;
    }

    private String getFavoriteEdgeId(Vertex userVertex, Vertex searchVertex) {
        return userVertex.getId() + "_" + SearchProperties.HAS_FAVORITED + "_" + searchVertex.getId();
    }
}
