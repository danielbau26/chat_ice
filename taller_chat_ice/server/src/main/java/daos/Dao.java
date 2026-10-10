package daos;

import java.util.List;

public interface Dao<T, K> {

    T save(T entity) throws Exception;

    T findById(K id) throws Exception;

    List<T> findAll() throws Exception;

    void update(T entity) throws Exception;

    void delete(K id) throws Exception;
}
