/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.clarin;

import java.util.Date;
import java.util.Objects;
import java.util.UUID;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;

import org.dspace.core.ReloadableEntity;
import org.dspace.eperson.EPerson;

/**
 * Entity representing an external authentication identity (e.g. OIDC subject) linked to an EPerson.
 * The combination of authMethod, provider and userId is unique.
 *
 * @author Milan Kuchtiak
 */
@Entity
@Table(name = "authentication_identity")
public class AuthenticationIdentity implements ReloadableEntity<Integer> {

    public static final String AUTH_METHOD_OIDC = "oidc";
    public static final String AUTH_METHOD_SHIBBOLETH = "shibboleth";

    @Id
    @Column(name = "id")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "authentication_identity_id_seq")
    @SequenceGenerator(name = "authentication_identity_id_seq", sequenceName = "authentication_identity_id_seq",
            allocationSize = 1)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "eperson_id", nullable = false)
    private EPerson ePerson;

    @Column(name = "auth_method", nullable = false)
    private String authMethod;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "created", nullable = false, updatable = false)
    private Date created;

    @Column(name = "last_login", nullable = false)
    private Date lastLogin;

    public AuthenticationIdentity() {
    }

    @Override
    public Integer getID() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public EPerson getEPerson() {
        return ePerson;
    }

    public void setEPerson(EPerson ePerson) {
        this.ePerson = ePerson;
    }

    public String getAuthMethod() {
        return authMethod;
    }

    public void setAuthMethod(String authMethod) {
        this.authMethod = authMethod;
    }

    /**
     * Returns the identity provider label, e.g. "einfra" for OIDC or IdP entityID for Shibboleth
     *
     * @return provider value
     */
    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    /**
     * Returns the user identifier assigned by the provider, e.g. OIDC "sub" claim
     *
     * @return userId value
     */
    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Date getCreated() {
        return created;
    }

    public void setCreated(Date created) {
        this.created = created;
    }

    public Date getLastLogin() {
        return lastLogin;
    }

    public void setLastLogin(Date lastLogin) {
        this.lastLogin = lastLogin;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AuthenticationIdentity that = (AuthenticationIdentity) o;
        return Objects.equals(id, that.id) &&
                Objects.equals(ePerson, that.ePerson) &&
                Objects.equals(authMethod, that.authMethod) &&
                Objects.equals(provider, that.provider) &&
                Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        UUID ePersonId = (ePerson != null) ? ePerson.getID() : null;
        return Objects.hash(id, ePersonId, authMethod, provider, userId);
    }

    @Override
    public String toString() {
        return "AuthenticationIdentity{" +
                "id: " + id +
                ", ePerson: " + (ePerson != null ? ePerson.getEmail() : "null") +
                ", authMethod: " + authMethod +
                ", provider: " + provider +
                ", userId: " + userId +
                ", created: " + created +
                ", lastLogin: " + lastLogin +
                '}';
    }
}