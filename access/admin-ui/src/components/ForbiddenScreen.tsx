/** Экран для 403: пользователь вошёл, но прав на раздел нет. */
export function ForbiddenScreen() {
  return (
    <section role="alert" className="forbidden">
      <h2>Нет прав</h2>
      <p>У вашей учётной записи нет доступа к этому разделу. Обратитесь к администратору.</p>
    </section>
  );
}
