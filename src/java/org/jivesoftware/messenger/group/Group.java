/**
 * $RCSfile$
 * $Revision$
 * $Date$
 *
 * Copyright (C) 2004 Jive Software. All rights reserved.
 *
 * This software is published under the terms of the GNU Public License (GPL),
 * a copy of which is included in this distribution.
 */

package org.jivesoftware.messenger.group;

import org.jivesoftware.util.Log;
import org.jivesoftware.util.Cacheable;
import org.jivesoftware.util.CacheSizes;
import org.jivesoftware.database.DbConnectionManager;
import org.jivesoftware.messenger.XMPPServer;
import org.jivesoftware.messenger.event.GroupEvent;
import org.jivesoftware.messenger.event.GroupEventDispatcher;
import org.jivesoftware.messenger.user.UserManager;
import org.jivesoftware.stringprep.Stringprep;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Groups organize users into a single entity for easier management.
 *
 * @see GroupManager#createGroup(String)
 *
 * @author Matt Tucker
 */
public class Group implements Cacheable {

    private static final String LOAD_PROPERTIES =
        "SELECT name, propValue FROM jiveGroupProp WHERE groupID=?";
    private static final String DELETE_PROPERTY =
        "DELETE FROM jiveGroupProp WHERE groupID=? AND name=?";
    private static final String UPDATE_PROPERTY =
        "UPDATE jiveGroupProp SET propValue=? WHERE name=? AND groupID=?";
    private static final String INSERT_PROPERTY =
        "INSERT INTO jiveGroupProp (groupID, name, propValue) VALUES (?, ?, ?)";

    private GroupProvider provider;
    private GroupManager groupManager;
    private String name;
    private String description;
    private Map<String, String> properties;
    private Collection<String> members;
    private Collection<String> administrators;

    /**
     * Constructs a new group.
     *
     * @param provider the group provider.
     * @param name the name.
     * @param description the description.
     * @param members a Collection of the group members.
     * @param administrators a Collection of the group administrators.
     */
    protected Group(GroupProvider provider, String name, String description,
            Collection<String> members, Collection<String> administrators)
    {
        this.provider = provider;
        this.groupManager = GroupManager.getInstance();
        this.name = name;
        this.description = description;
        this.members = members;
        this.administrators = administrators;
    }

    /**
     * Returns the name of the group. For example, 'XYZ Admins'.
     *
     * @return the name of the group.
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the name of the group. For example, 'XYZ Admins'. This
     * method is restricted to those with group administration permission.
     *
     * @param name the name for the group.
     */
    public void setName(String name) {
        try {
            String originalName = this.name;
            provider.setName(this.name, name);
            groupManager.groupCache.remove(this.name);
            this.name = name;
            groupManager.groupCache.put(name, this);

            // Fire event.
            Map params = new HashMap();
            params.put("type", "nameModified");
            params.put("originalValue", originalName);
            GroupEvent event = new GroupEvent(GroupEvent.EventType.group_modified,
                    this, params);
            GroupEventDispatcher.dispatchEvent(event);
        }
        catch (Exception e) {
            Log.error(e);
        }
    }

    /**
     * Returns the description of the group. The description often
     * summarizes a group's function, such as 'Administrators of the XYZ forum'.
     *
     * @return the description of the group.
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the description of the group. The description often
     * summarizes a group's function, such as 'Administrators of
     * the XYZ forum'. This method is restricted to those with group
     * administration permission.
     *
     * @param description the description of the group.
     */
    public void setDescription(String description) {
        try {
            String originalDescription = this.description;
            provider.setDescription(name, description);
            this.description = description;
            // Fire event.
            Map params = new HashMap();
            params.put("type", "descriptionModified");
            params.put("originalValue", originalDescription);
            GroupEvent event = new GroupEvent(GroupEvent.EventType.group_modified,
                    this, params);
            GroupEventDispatcher.dispatchEvent(event);
        }
        catch (Exception e) {
            Log.error(e);
        }
    }

    /**
     * Returns all extended properties of the group. Groups
     * have an arbitrary number of extended properties.
     *
     * @return the extended properties.
     */
    public Map<String,String> getProperties() {
        synchronized (this) {
            if (properties == null) {
                properties = new ConcurrentHashMap<String, String>();
                loadProperties();
            }
        }
        // Return a wrapper that will intercept add and remove commands.
        return new PropertiesMap();
    }

    /**
     * Returns a Collection of the group administrators.
     *
     * @return a Collection of the group administrators.
     */
    public Collection<String> getAdmins() {
        // Return a wrapper that will intercept add and remove commands.
        return new MemberCollection(administrators, true);
    }

    /**
     * Returns a Collection of the group members.
     *
     * @return a Collection of the group members.
     */
    public Collection<String> getMembers() {
        // Return a wrapper that will intercept add and remove commands.
        return new MemberCollection(members, false);
    }

    /**
     * Returns true if the provided username belongs to a user of the group.
     *
     * @param username the username to check.
     * @return true if the provided username belongs to a user of the group.
     */
    public boolean isUser(String username) {
        return members.contains(username) || administrators.contains(username);
    }

    public int getCachedSize() {
        // Approximate the size of the object in bytes by calculating the size
        // of each field.
        int size = 0;
        size += CacheSizes.sizeOfObject();              // overhead of object
        size += CacheSizes.sizeOfString(name);
        size += CacheSizes.sizeOfString(description);
        return size;
    }

    /**
     * Collection implementation that notifies the GroupProvider of any
     * changes to the collection.
     */
    private class MemberCollection extends AbstractCollection {

        private Collection<String> users;
        private boolean adminCollection;

        public MemberCollection(Collection<String> users, boolean adminCollection) {
            this.users = users;
            this.adminCollection = adminCollection;
        }

        public Iterator iterator() {
            return new Iterator() {

                Iterator iter = users.iterator();
                Object current = null;

                public boolean hasNext() {
                    return iter.hasNext();
                }

                public Object next() {
                    current = iter.next();
                    return current;
                }

                public void remove() {
                    if (current == null) {
                        throw new IllegalStateException();
                    }
                    String user = (String)current;
                    // Update the group users' roster -- TODO: remove and use event.
                    XMPPServer.getInstance().getRosterManager().groupUserDeleted(Group.this, user);
                    // Remove the user from the collection in memory.
                    iter.remove();
                    // Remove the group user from the backend store.
                    provider.deleteMember(name, user);
                    // Fire event.
                    if (adminCollection) {
                        Map params = new HashMap();
                        params.put("admin", user);
                        GroupEvent event = new GroupEvent(GroupEvent.EventType.admin_removed,
                                Group.this, params);
                        GroupEventDispatcher.dispatchEvent(event);
                    }
                    else {
                        Map params = new HashMap();
                        params.put("member", user);
                        GroupEvent event = new GroupEvent(GroupEvent.EventType.member_removed,
                                Group.this, params);
                        GroupEventDispatcher.dispatchEvent(event);
                    }
                }
            };
        }

        public int size() {
            return users.size();
        }

        public boolean add(Object member) {
            String username = (String) member;
            try {
                username = Stringprep.nodeprep(username);
                UserManager.getInstance().getUser(username);
            }
            catch (Exception e) {
                throw new IllegalArgumentException("Invalid user.", e);
            }
            if (adminCollection) {
                if (members.contains(username)) {
                    throw new IllegalArgumentException("The user is already a member of the group");
                }
            }
            else {
                if (administrators.contains(username)) {
                    throw new IllegalArgumentException("The user is already an admin of the group");
                }
            }
            if (users.add(username)) {
                // Add the group user to the backend store.
                provider.addMember(name, username, adminCollection);

                // Fire event.
                if (adminCollection) {
                    Map params = new HashMap();
                    params.put("admin", username);
                    GroupEvent event = new GroupEvent(GroupEvent.EventType.admin_added,
                            Group.this, params);
                    GroupEventDispatcher.dispatchEvent(event);
                }
                else {
                    Map params = new HashMap();
                    params.put("member", username);
                    GroupEvent event = new GroupEvent(GroupEvent.EventType.member_added,
                            Group.this, params);
                    GroupEventDispatcher.dispatchEvent(event);
                }

                // Update the group users' roster -- TODO: remove and use event.
                XMPPServer.getInstance().getRosterManager().groupUserAdded(Group.this, username);
                return true;
            }
            return false;
        }
    }

    /**
     * Map implementation that updates the database when properties are modified.
     */
    private class PropertiesMap extends AbstractMap {

        public Object put(Object key, Object value) {
            if (properties.containsKey(key)) {
                String originalValue = properties.get(key);
                updateProperty((String)key, (String)value);
                // Fire event.
                Map params = new HashMap();
                params.put("type", "propertyModified");
                params.put("propertyKey", key);
                params.put("originalValue", originalValue);
                GroupEvent event = new GroupEvent(GroupEvent.EventType.group_modified,
                        Group.this, params);
                GroupEventDispatcher.dispatchEvent(event);
            }
            else {
                insertProperty((String)key, (String)value);
                // Fire event.
                Map params = new HashMap();
                params.put("type", "propertyAdded");
                params.put("propertyKey", key);
                GroupEvent event = new GroupEvent(GroupEvent.EventType.group_modified,
                        Group.this, params);
                GroupEventDispatcher.dispatchEvent(event);
            }
            return properties.put((String)key, (String)value);
        }

        public Set<Entry> entrySet() {
            return new PropertiesEntrySet();
        }
    }

    /**
     * Set implementation that updates the database when properties are deleted.
     */
    private class PropertiesEntrySet extends AbstractSet {

        public int size() {
            return properties.entrySet().size();
        }

        public Iterator iterator() {
            return new Iterator() {

                Iterator iter = properties.entrySet().iterator();
                Map.Entry current = null;

                public boolean hasNext() {
                    return iter.hasNext();
                }

                public Object next() {
                    current = (Map.Entry)iter.next();
                    return iter.next();
                }

                public void remove() {
                    if (current == null) {
                        throw new IllegalStateException();
                    }
                    deleteProperty((String)current.getKey());
                    iter.remove();
                    // Fire event.
                    Map params = new HashMap();
                    params.put("type", "propertyDeleted");
                    params.put("propertyKey", current.getKey());
                    GroupEvent event = new GroupEvent(GroupEvent.EventType.group_modified,
                            Group.this, params);
                    GroupEventDispatcher.dispatchEvent(event);
                }
            };
        }
    }

    private void loadProperties() {
        Connection con = null;
        PreparedStatement pstmt = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(LOAD_PROPERTIES);
            pstmt.setString(1, name);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                properties.put(rs.getString(1), rs.getString(2));
            }
            rs.close();
        }
        catch (SQLException sqle) {
            Log.error(sqle);
        }
        finally {
            try { if (pstmt != null) pstmt.close(); }
            catch (Exception e) { Log.error(e); }
            try { if (con != null) con.close(); }
            catch (Exception e) { Log.error(e); }
        }
    }

    public void insertProperty(String propName, String propValue) {
        Connection con = null;
        PreparedStatement pstmt = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(INSERT_PROPERTY);
            pstmt.setString(1, name);
            pstmt.setString(2, propName);
            pstmt.setString(3, propValue);
            pstmt.executeUpdate();
        }
        catch (SQLException e) {
            Log.error(e);
        }
        finally {
            try { if (pstmt != null) pstmt.close(); }
            catch (Exception e) { Log.error(e); }
            try { if (con != null) con.close(); }
            catch (Exception e) { Log.error(e); }
        }
    }

    public void updateProperty(String propName, String propValue) {
        Connection con = null;
        PreparedStatement pstmt = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(UPDATE_PROPERTY);
            pstmt.setString(1, propValue);
            pstmt.setString(2, propName);
            pstmt.setString(3, name);
            pstmt.executeUpdate();
        }
        catch (SQLException e) {
            Log.error(e);
        }
        finally {
            try { if (pstmt != null) pstmt.close(); }
            catch (Exception e) { Log.error(e); }
            try { if (con != null) con.close(); }
            catch (Exception e) { Log.error(e); }
        }
    }

    public void deleteProperty(String propName) {
        Connection con = null;
        PreparedStatement pstmt = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(DELETE_PROPERTY);
            pstmt.setString(1, name);
            pstmt.setString(2, propName);
            pstmt.executeUpdate();
        }
        catch (SQLException e) {
            Log.error(e);
        }
        finally {
            try { if (pstmt != null) pstmt.close(); }
            catch (Exception e) { Log.error(e); }
            try { if (con != null) con.close(); }
            catch (Exception e) { Log.error(e); }
        }
    }
}