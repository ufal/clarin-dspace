/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.clarin;

import java.util.Objects;
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

import org.dspace.content.Item;
import org.dspace.core.ReloadableEntity;
import org.dspace.eperson.EPerson;

/**
 * Entity representing matomo_report.
 *
 * @author Milan Kuchtiak
 */
@Entity
@Table(name = "matomo_report")
public class MatomoReport implements ReloadableEntity<Integer> {

    @Id
    @Column(name = "id")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "matomo_report_id_seq")
    @SequenceGenerator(name = "matomo_report_id_seq", sequenceName = "matomo_report_id_seq", allocationSize = 1)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "eperson_id")
    private EPerson ePerson;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id")
    private Item item;

    public MatomoReport() {
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

    public Item getItem() {
        return item;
    }

    public void setItem(Item item) {
        this.item = item;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        MatomoReport that = (MatomoReport) o;
        return Objects.equals(id, that.id)
                && Objects.equals(ePerson.getID(), that.ePerson.getID())
                && Objects.equals(item.getID(), that.item.getID());
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ePerson.getID(), item.getID());
    }

    @Override
    public String toString() {
        return "MatomoReport{" +
                "id=" + id +
                ", ePerson=" + ePerson +
                ", item=" + item +
                '}';
    }
}
